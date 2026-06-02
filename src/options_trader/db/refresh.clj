(ns options-trader.db.refresh
  (:require [clojure.string                  :as str]
            [next.jdbc                       :as jdbc]
            [next.jdbc.result-set            :as rs]
            [taoensso.timbre                 :as log]
            [cheshire.core                   :as json]
            [options-trader.data.ibkr        :as ibkr]
            [options-trader.data.universes   :as universes]
            [options-trader.data.news        :as news]
            [options-trader.data.edgar       :as edgar]
            [options-trader.data.fundamentals :as fundamentals]
            [options-trader.indicators.engine :as indicators]
            [options-trader.portfolio.core   :as portfolio])
  (:import [java.time LocalDate LocalDateTime Instant ZoneId Duration]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit]
           [java.sql Timestamp]
           [java.util.concurrent Executors ExecutorCompletionService TimeUnit]))

(def ^:private news-date-fmt
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.0"))

(def ^:private as-lower {:builder-fn rs/as-unqualified-lower-maps})

(defn- now-ts [] (Timestamp. (System/currentTimeMillis)))

;;; ── refresh_log row helpers ────────────────────────────────────────────────

(defn- next-log-id [ds]
  (-> (jdbc/execute-one! ds
        ["SELECT COALESCE(MAX(id), 0) + 1 AS n FROM refresh_log"] as-lower)
      :n))

(defn- log-start! [ds task symbol]
  (let [id (next-log-id ds)]
    (jdbc/execute-one! ds
      ["INSERT INTO refresh_log (id, task, symbol, started_at, status)
        VALUES (?, ?, ?, ?, 'running')"
       id (name task) symbol (now-ts)])
    id))

(defn- log-finish! [ds id status error]
  (jdbc/execute-one! ds
    ["UPDATE refresh_log SET finished_at = ?, status = ?, error = ?
      WHERE id = ?"
     (now-ts) status error id]))

(defmacro with-log [ds task symbol & body]
  `(let [id#    (log-start! ~ds ~task ~symbol)
         start# (System/currentTimeMillis)]
     (log/infof "════════ %s ════════" ~task)
     (try
       (let [r# (do ~@body)]
         (log-finish! ~ds id# "ok" nil)
         (log/infof "════════ %s done in %.1fs ════════"
                    ~task (/ (- (System/currentTimeMillis) start#) 1000.0))
         r#)
       (catch Throwable t#
         (log-finish! ~ds id# "error" (.getMessage t#))
         (log/warnf "════════ %s FAILED in %.1fs: %s ════════"
                    ~task
                    (/ (- (System/currentTimeMillis) start#) 1000.0)
                    (.getMessage t#))
         (throw t#)))))

;;; ── Async helpers ──────────────────────────────────────────────────────────

(defn- await-batch [start-fn timeout-ms]
  (let [p (promise)
        r (start-fn (fn [evs] (deliver p evs)))]
    (cond
      (= :unavailable r) :unavailable
      :else
      (let [v (deref p timeout-ms ::timeout)]
        (if (= ::timeout v) ::timeout v)))))

(def ^:dynamic *fail-fast-consecutive*
  "Abort a refresh run if this many symbols in a row time out or come back
   :unavailable. Catches a broken TWS connection on call ~6 instead of after
   the whole universe."
  5)

(def ^:dynamic *parallelism*
  "How many IB requests to keep in flight concurrently for symbol-by-symbol
   refresh tasks. Capped well under IB's 50-req/sec global pacer and 50
   concurrent-historical-data ceiling. Override per call via opts or by
   rebinding for tests."
  16)

(defn- ->iso-date [s]
  (let [s (str s)]
    (if (re-matches #"\d{8}" s)
      (str (subs s 0 4) "-" (subs s 4 6) "-" (subs s 6 8))
      s)))

(defn- each-symbol!
  "Fan f over symbols with bounded concurrency (*parallelism* in-flight at a
   time), tally outcomes as they complete, and log per-symbol progress.

   f may return:
     {:rows N}              — ok
     {:timeout? true}       — timeout
     :unavailable           — unavailable
     nil / anything else    — empty
   A thrown exception is caught and counted as error.

   Aborts the whole run with ex-info if *fail-fast-consecutive* completions in
   a row come back bad — almost always means TWS is wedged and the remaining
   calls would all time out. Running futures are interrupted on abort."
  [task symbols f]
  (let [start      (System/currentTimeMillis)
        total      (count symbols)
        n          (max 1 (min *parallelism* (max 1 total)))
        pool       (Executors/newFixedThreadPool n)
        ecs        (ExecutorCompletionService. pool)
        acc        (atom {:symbols-ok 0 :symbols-err 0 :symbols-empty 0
                          :timeouts 0 :unavailable 0 :rows 0})
        consec-bad (atom 0)]
    (log/infof "%s: %d symbols, parallelism=%d" task total n)
    (doseq [sym symbols]
      (.submit ecs ^Callable
        (fn []
          (try [sym (f sym)]
               (catch Throwable t [sym t])))))
    (try
      (loop [i 1]
        (when (<= i total)
          (let [[sym r]
                (try (.get (.take ecs))
                     (catch Throwable t [nil t]))

                outcome
                (cond
                  (instance? Throwable r)
                  (do (log/warnf "%s [%d/%d] %s failed: %s"
                                 task i total sym (.getMessage ^Throwable r))
                      (swap! acc update :symbols-err inc) :bad)

                  (= :unavailable r)
                  (do (log/warnf "%s [%d/%d] %s unavailable (TWS rejected)"
                                 task i total sym)
                      (swap! acc update :unavailable inc) :bad)

                  (:timeout? r)
                  (do (log/warnf "%s [%d/%d] %s timeout — IB never returned"
                                 task i total sym)
                      (swap! acc update :timeouts inc) :bad)

                  (map? r)
                  (let [rows (or (:rows r) 0)]
                    (log/infof "%s [%d/%d] %s ok (%d rows)" task i total sym rows)
                    (swap! acc (fn [m] (-> m
                                           (update :symbols-ok inc)
                                           (update :rows + rows))))
                    :ok)

                  :else
                  (do (log/warnf "%s [%d/%d] %s no data" task i total sym)
                      (swap! acc update :symbols-empty inc) :empty))]
            (if (= :bad outcome)
              (swap! consec-bad inc)
              (reset! consec-bad 0))
            (when (>= @consec-bad *fail-fast-consecutive*)
              (.shutdownNow pool)
              (throw (ex-info (format "%s aborted: %d consecutive failures — is TWS connected?"
                                      (name task) @consec-bad)
                              (assoc @acc :task task :aborted-at i :total total)))))
          (recur (inc i))))
      (finally
        (.shutdown pool)
        (try (.awaitTermination pool 5 TimeUnit/SECONDS)
             (catch InterruptedException _))))
    (assoc @acc
           :task       task
           :n-symbols  total
           :elapsed-ms (- (System/currentTimeMillis) start))))

;;; ── Bars (daily) — incremental ─────────────────────────────────────────────

(defn latest-bar-date [ds sym]
  (some-> (jdbc/execute-one! ds
            ["SELECT MAX(bar_date) AS d FROM bars_daily WHERE symbol = ?" sym]
            as-lower)
          :d))

(defn duration-for-gap [last-d]
  (if (nil? last-d)
    "5 Y"
    (let [^LocalDate ld (if (instance? LocalDate last-d) last-d (.toLocalDate last-d))
          days (.until ld (LocalDate/now) ChronoUnit/DAYS)]
      (cond
        (<= days 1)  "2 D"
        (<= days 7)  "1 W"
        (<= days 30) "1 M"
        (<= days 90) "3 M"
        :else        "1 Y"))))

(defn- insert-bars-daily! [ds bars]
  (let [rows (->> bars
                  (keep (fn [b]
                          (let [d (or (:bar-date b) (:date b) (:time b))]
                            (when (and d (:close b))
                              [(:symbol b) (->iso-date d)
                               (:open b) (:high b) (:low b) (:close b)
                               (or (:volume b) 0)]))))
                  vec)]
    (when (seq rows)
      (jdbc/execute-batch! ds
        "INSERT INTO bars_daily (symbol, bar_date, open, high, low, close, volume)
         VALUES (?,?,?,?,?,?,?)
         ON CONFLICT (symbol, bar_date) DO UPDATE SET
           open=excluded.open, high=excluded.high, low=excluded.low,
           close=excluded.close, volume=excluded.volume" rows {}))
    (count rows)))

(defn refresh-bars-daily!
  [{:keys [conn ds symbols timeout-ms compute-indicators?]
    :or   {timeout-ms 15000 compute-indicators? true}}]
  (with-log ds :bars-daily nil
    (let [result (each-symbol! :bars-daily symbols
                   (fn [sym]
                     (let [last-d (latest-bar-date ds sym)
                           dur    (duration-for-gap last-d)
                           bars   (await-batch
                                    (fn [cb]
                                      (ibkr/req-historical-bars
                                        conn (ibkr/->contract sym) "1 day" dur cb))
                                    timeout-ms)]
                       (cond
                         (= :unavailable bars) :unavailable
                         (= ::timeout    bars) {:timeout? true}
                         (sequential?    bars) {:rows (insert-bars-daily!
                                                        ds (map #(assoc % :symbol sym) bars))}
                         :else                 nil))))]
      (cond-> result
        compute-indicators?
        (assoc :indicators
               (let [t0 (System/currentTimeMillis)]
                 (log/info "computing derived indicators (this can take a while)...")
                 (try
                   (let [r (indicators/refresh-derived-indicators! ds)]
                     (log/infof "derived indicators done in %.1fs"
                                (/ (- (System/currentTimeMillis) t0) 1000.0))
                     r)
                   (catch Throwable t
                     (log/warnf t "indicator recompute failed")
                     :error))))))))

;;; ── Bars (intraday) — incremental ──────────────────────────────────────────

(defn latest-intraday-ts [ds sym bar-size]
  (some-> (jdbc/execute-one! ds
            ["SELECT MAX(bar_ts) AS t FROM bars_intraday
              WHERE symbol = ? AND bar_size = ?" sym bar-size]
            as-lower)
          :t))

(defn intraday-duration-for-gap [last-ts bar-size]
  (if (nil? last-ts)
    (case bar-size
      "1 min"   "2 D"
      "5 mins"  "1 W"
      "15 mins" "2 W"
      "30 mins" "1 M"
      "1 hour"  "1 M"
      "5 D")
    (let [^Instant inst (cond
                          (instance? Instant       last-ts) last-ts
                          (instance? Timestamp     last-ts) (.toInstant ^Timestamp last-ts)
                          (instance? LocalDateTime last-ts) (.toInstant
                                                              ^LocalDateTime last-ts
                                                              (.getOffset (.getRules (ZoneId/systemDefault))
                                                                          (LocalDateTime/now)))
                          :else (Instant/now))
          hours  (.toHours (Duration/between inst (Instant/now)))]
      (cond
        (<= hours 4)   "1 D"
        (<= hours 24)  "2 D"
        (<= hours 72)  "1 W"
        (<= hours 168) "2 W"
        :else          "1 M"))))

(defn- insert-bars-intraday! [ds bar-size bars]
  (let [rows (->> bars
                  (keep (fn [b]
                          (let [ts (or (:bar-ts b) (:date b) (:time b))]
                            (when (and ts (:close b))
                              [(:symbol b) ts bar-size
                               (:open b) (:high b) (:low b) (:close b)
                               (or (:volume b) 0)]))))
                  vec)]
    (when (seq rows)
      (jdbc/execute-batch! ds
        "INSERT INTO bars_intraday (symbol, bar_ts, bar_size, open, high, low, close, volume)
         VALUES (?,?,?,?,?,?,?,?)
         ON CONFLICT (symbol, bar_ts, bar_size) DO UPDATE SET
           open=excluded.open, high=excluded.high, low=excluded.low,
           close=excluded.close, volume=excluded.volume" rows {}))
    (count rows)))

(defn refresh-bars-intraday!
  [{:keys [conn ds symbols bar-size timeout-ms]
    :or   {bar-size "15 mins" timeout-ms 15000}}]
  (with-log ds :bars-intraday nil
    (each-symbol! :bars-intraday symbols
      (fn [sym]
        (let [last-ts (latest-intraday-ts ds sym bar-size)
              dur     (intraday-duration-for-gap last-ts bar-size)
              bars    (await-batch
                        (fn [cb]
                          (ibkr/req-historical-bars
                            conn (ibkr/->contract sym) bar-size dur cb))
                        timeout-ms)]
          (cond
            (= :unavailable bars) :unavailable
            (= ::timeout    bars) {:timeout? true}
            (sequential?    bars) {:rows (insert-bars-intraday!
                                           ds bar-size (map #(assoc % :symbol sym) bars))}
            :else                 nil))))))

;;; ── News (headlines + sentiment) — incremental ─────────────────────────────

(defn latest-news-published [ds sym]
  (some-> (jdbc/execute-one! ds
            ["SELECT MAX(published_at) AS t FROM news WHERE symbol = ?" sym]
            as-lower)
          :t))

(defn news-start-date-for-gap [last-ts]
  (if (nil? last-ts)
    (.format (.minusDays (LocalDateTime/now) 30) news-date-fmt)
    (let [^Instant inst (cond
                          (instance? Instant       last-ts) last-ts
                          (instance? Timestamp     last-ts) (.toInstant ^Timestamp last-ts)
                          :else (Instant/now))
          ldt (LocalDateTime/ofInstant inst (ZoneId/systemDefault))]
      (.format ldt news-date-fmt))))


(defn- insert-news-rows! [ds sym headlines _sentiment]
  (let [rows (->> headlines
                  (keep (fn [h]
                          (when-let [id (or (:article-id h) (:id h))]
                            (let [raw  (or (:headline h) (:title h) "")
                                  ;; Per-article sentiment: K: from provider
                                  ;; metadata if present, else lexicon.
                                  sent (name (news/classify (news/score-headline raw)))]
                              [id sym raw
                               (or (:url h) "")
                               (or (:provider-code h) (:source h) "")
                               (or (:published-at h) (:time h))
                               sent
                               (json/generate-string h)]))))
                  vec)]
    (when (seq rows)
      (jdbc/execute-batch! ds
        "INSERT INTO news (id, symbol, title, url, source, published_at, sentiment, data)
         VALUES (?,?,?,?,?,?,?,?)
         ON CONFLICT (id) DO UPDATE SET
           title=excluded.title, url=excluded.url,
           sentiment=excluded.sentiment, data=excluded.data" rows {}))
    (count rows)))

(defn refresh-news!
  [{:keys [conn ds symbols params timeout-ms]
    :or   {params {} timeout-ms 15000}}]
  (with-log ds :news nil
    (let [src (news/make-source {:type :ibkr :ib-client conn})]
      (each-symbol! :news symbols
        (fn [sym]
          (let [start   (news-start-date-for-gap (latest-news-published ds sym))
                params  (merge {:limit 25} params {:start-date start})
                p       (promise)
                rid     (news/fetch-news-headlines sym params src
                          (fn [evs] (deliver p evs)))]
            (cond
              (= :unavailable rid) :unavailable
              :else
              (let [evs (deref p timeout-ms ::timeout)]
                (if (= ::timeout evs)
                  {:timeout? true}
                  (let [hs  (filter #(or (:article-id %) (:id %)) evs)
                        sen (news/fetch-news-sentiment sym params src)]
                    {:rows (insert-news-rows! ds sym hs sen)}))))))))))

;;; ── Fundamentals (EDGAR) ───────────────────────────────────────────────────

(defn refresh-fundamentals!
  [{:keys [ds symbols]}]
  (with-log ds :fundamentals nil
    (log/infof "fundamentals: pulling from EDGAR (%s)..."
               (if symbols (str (count symbols) " symbols") "all symbols in bars_daily"))
    (let [t0 (System/currentTimeMillis)
          r  (indicators/refresh-fundamentals! ds (cond-> {} symbols (assoc :symbols symbols)))]
      (log/infof "fundamentals done in %.1fs"
                 (/ (- (System/currentTimeMillis) t0) 1000.0))
      r)))

;;; ── Filings (EDGAR) — incremental ──────────────────────────────────────────

(defn latest-filing-date [ds sym]
  (some-> (jdbc/execute-one! ds
            ["SELECT MAX(filed_at) AS d FROM filings WHERE symbol = ?" sym]
            as-lower)
          :d))

(defn filings-start-date-for-gap [last-d]
  (cond
    (nil? last-d) nil
    (instance? LocalDate last-d) (.toString ^LocalDate last-d)
    :else (.toString (.toLocalDate ^java.sql.Date last-d))))


(defn- insert-filings! [ds sym filings]
  (let [rows (->> filings
                  (keep (fn [f]
                          (when-let [acc (or (:accession f) (:accession-no f))]
                            [acc sym (:cik f) (:form-type f)
                             (some-> (:filed-at f) ->iso-date)
                             (some-> (:period f) ->iso-date)
                             (json/generate-string f)])))
                  vec)]
    (when (seq rows)
      (jdbc/execute-batch! ds
        "INSERT INTO filings (accession, symbol, cik, form_type, filed_at, period, data)
         VALUES (?,?,?,?,?,?,?)
         ON CONFLICT (accession) DO UPDATE SET
           form_type=excluded.form_type, filed_at=excluded.filed_at,
           period=excluded.period, data=excluded.data" rows {}))
    (count rows)))

(defn refresh-filings!
  [{:keys [ds symbols params] :or {params {}}}]
  (with-log ds :filings nil
    (each-symbol! :filings symbols
      (fn [sym]
        (let [start  (filings-start-date-for-gap (latest-filing-date ds sym))
              params (cond-> params start (assoc :start-date start))
              r      (edgar/fetch-edgar-filings sym params (edgar/default-source))
              fs     (cond
                       (sequential? r) r
                       (map? r)        (or (:filings r) [])
                       :else           [])]
          {:rows (insert-filings! ds sym fs)})))))

;;; ── Universes ──────────────────────────────────────────────────────────────

(defn- current-members [ds u]
  (->> (jdbc/execute! ds
         ["SELECT symbol FROM universe_members WHERE universe = ?" u]
         as-lower)
       (map :symbol)
       set))

(defn- log-drift! [ds u action sym]
  (let [id (-> (jdbc/execute-one! ds
                 ["SELECT COALESCE(MAX(id), 0) + 1 AS n FROM universe_drift_log"]
                 as-lower) :n)]
    (jdbc/execute-one! ds
      ["INSERT INTO universe_drift_log (id, universe, symbol, action)
        VALUES (?, ?, ?, ?)" id u sym action])))

(defn- sync-universe! [ds u tickers]
  (let [prev (current-members ds u)
        next (set tickers)
        added   (clojure.set/difference next prev)
        removed (clojure.set/difference prev next)]
    (jdbc/execute-one! ds
      ["INSERT INTO universes (name, description) VALUES (?, ?)
        ON CONFLICT (name) DO NOTHING" u (str u " (auto)")])
    (doseq [s added]
      (jdbc/execute-one! ds
        ["INSERT INTO universe_members (universe, symbol) VALUES (?, ?)
          ON CONFLICT DO NOTHING" u s])
      (log-drift! ds u "added" s))
    (doseq [s removed]
      (jdbc/execute-one! ds
        ["DELETE FROM universe_members WHERE universe = ? AND symbol = ?" u s])
      (log-drift! ds u "removed" s))
    {:added (count added) :removed (count removed) :total (count next)}))

(defn refresh-universes!
  [{:keys [ds sources] :or {sources [:sp500 :nasdaq100]}}]
  (with-log ds :universes nil
    (into {}
      (for [src sources
            :let [tickers (universes/ticker-fetch src)]
            :when (sequential? tickers)]
        [src (sync-universe! ds (name src) tickers)]))))

;;; ── Portfolio ──────────────────────────────────────────────────────────────

(defn refresh-portfolio!
  [{:keys [conn ds account-id]}]
  (with-log ds :portfolio account-id
    (let [src (portfolio/make-source
                {:portfolio {:source :ibkr :account-id account-id}}
                conn ds)]
      (portfolio/refresh! src ds account-id))))

;;; ── Ping (smoke test) ──────────────────────────────────────────────────────

(defn ping
  [{:keys [conn ds]}]
  (let [tws? (and conn (ibkr/is-connected?))
        db?  (try (jdbc/execute-one! ds ["SELECT 1 AS n"] as-lower) true
                  (catch Throwable _ false))]
    {:tws-connected? (boolean tws?)
     :db-reachable?  (boolean db?)
     :ok?            (and tws? db?)}))
