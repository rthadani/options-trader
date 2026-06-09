(ns options-trader.db.refresh
  (:require [clojure.string                  :as str]
            [next.jdbc                       :as jdbc]
            [next.jdbc.result-set            :as rs]
            [taoensso.timbre                 :as log]
            [cheshire.core                   :as json]
            [options-trader.data.ibkr        :as ibkr]
            [options-trader.data.ibkr.pacer  :as pacer]
            [options-trader.data.universes   :as universes]
            [options-trader.data.news        :as news]
            [options-trader.data.edgar       :as edgar]
            [options-trader.data.fundamentals :as fundamentals]
            [options-trader.data.yfinance    :as yf]
            [options-trader.db.queries.refresh :as q]
            [options-trader.indicators.engine :as indicators]
            [options-trader.portfolio.core   :as portfolio]
            [options-trader.util :refer [as-lower]])
  (:import [java.time LocalDate LocalDateTime Instant ZoneId Duration]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit]
           [java.sql Timestamp]
           [java.util.concurrent Executors ExecutorCompletionService TimeUnit]))

(def ^:private news-date-fmt
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.0"))

(defn- now-ts [] (Timestamp. (System/currentTimeMillis)))

;;; ── refresh_log row helpers ────────────────────────────────────────────────

(defn- log-start! [ds task symbol]
  (let [id (q/next-refresh-log-id ds)]
    (q/insert-refresh-log! ds {:id id :task (name task)
                                :symbol symbol :started-at (now-ts)})
    id))

(defn- log-finish! [ds id status error]
  (q/finish-refresh-log! ds {:id id :status status :error error
                              :finished-at (now-ts)}))

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

(defn- await-batch
  "Submit an async batch request and wait. Returns the events vector,
   :unavailable, or ::timeout. Optional cancel-fn is called with the
   req-id on timeout so the caller can free server-side resources."
  ([start-fn timeout-ms] (await-batch start-fn timeout-ms nil))
  ([start-fn timeout-ms cancel-fn]
   (let [p (promise)
         r (start-fn (fn [evs] (deliver p evs)))]
     (cond
       (= :unavailable r) :unavailable
       :else
       (let [v (deref p timeout-ms ::timeout)]
         (if (= ::timeout v)
           (do (when (and cancel-fn (number? r))
                 (try (cancel-fn r) (catch Throwable _)))
               ::timeout)
           v))))))

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

(def ^:private zero-tally
  {:symbols-ok 0 :symbols-err 0 :symbols-empty 0
   :timeouts   0 :unavailable 0 :rows 0})

(defn- classify
  "Pure function: classify one worker result into [tally-update outcome].
   tally-update is a fn that takes the running tally and returns the new one;
   outcome is :ok / :bad / :empty for the consec-bad streak counter."
  [task i total sym r]
  (cond
    (instance? Throwable r)
    [(fn [t] (update t :symbols-err inc))
     :bad
     (format "%s [%d/%d] %s failed: %s" task i total sym (.getMessage ^Throwable r))]

    (= :unavailable r)
    [(fn [t] (update t :unavailable inc))
     :bad
     (format "%s [%d/%d] %s unavailable (TWS rejected)" task i total sym)]

    (:timeout? r)
    [(fn [t] (update t :timeouts inc))
     :bad
     (format "%s [%d/%d] %s timeout — IB never returned" task i total sym)]

    (map? r)
    (let [rows (or (:rows r) 0)]
      [(fn [t] (-> t (update :symbols-ok inc) (update :rows + rows)))
       :ok
       (format "%s [%d/%d] %s ok (%d rows)" task i total sym rows)])

    :else
    [(fn [t] (update t :symbols-empty inc))
     :empty
     (format "%s [%d/%d] %s no data" task i total sym)]))

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
  (let [start (System/currentTimeMillis)
        total (count symbols)
        n     (max 1 (min *parallelism* (max 1 total)))
        pool  (Executors/newFixedThreadPool n)
        ecs   (ExecutorCompletionService. pool)]
    (log/infof "%s: %d symbols, parallelism=%d" task total n)
    (doseq [sym symbols]
      (.submit ecs ^Callable
        (fn [] (try [sym (f sym)] (catch Throwable t [sym t])))))
    (let [final-tally
          (try
            (loop [i 1, tally zero-tally, consec-bad 0]
              (if (> i total)
                tally
                (let [[sym r] (try (.get (.take ecs))
                                   (catch Throwable t [nil t]))
                      [update-tally outcome msg] (classify task i total sym r)
                      tally'      (update-tally tally)
                      consec-bad' (if (= :bad outcome) (inc consec-bad) 0)]
                  (if (= :ok outcome) (log/info msg) (log/warn msg))
                  (when (>= consec-bad' *fail-fast-consecutive*)
                    (.shutdownNow pool)
                    (throw (ex-info
                             (format "%s aborted: %d consecutive failures — is TWS connected?"
                                     (name task) consec-bad')
                             (assoc tally' :task task :aborted-at i :total total))))
                  (recur (inc i) tally' consec-bad'))))
            (finally
              (.shutdown pool)
              (try (.awaitTermination pool 5 TimeUnit/SECONDS)
                   (catch InterruptedException _))))]
      (assoc final-tally
             :task       task
             :n-symbols  total
             :elapsed-ms (- (System/currentTimeMillis) start)))))

;;; ── Bars (daily) — incremental ─────────────────────────────────────────────

(defn latest-bar-date [ds sym]
  (q/latest-bar-date ds sym))

(defn- duration-for-gap* [last-d nil-default]
  (if (nil? last-d)
    nil-default
    (let [^LocalDate ld (if (instance? LocalDate last-d) last-d (.toLocalDate last-d))
          days (.until ld (LocalDate/now) ChronoUnit/DAYS)]
      (cond
        (<= days 1)  "2 D"
        (<= days 7)  "1 W"
        (<= days 30) "1 M"
        (<= days 90) "3 M"
        :else        "1 Y"))))

(defn duration-for-gap [last-d]
  (duration-for-gap* last-d "5 Y"))

(defn- insert-bars-daily! [ds bars]
  (let [rows (->> bars
                  (keep (fn [b]
                          (let [d (or (:bar-date b) (:date b) (:time b))]
                            (when (and d (:close b))
                              [(:symbol b) (->iso-date d)
                               (:open b) (:high b) (:low b) (:close b)
                               (or (:volume b) 0)]))))
                  vec)]
    (q/insert-bars-daily-batch! ds rows)))

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
  (q/latest-intraday-ts ds sym bar-size))

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
    (q/insert-bars-intraday-batch! ds rows)))

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
  (q/latest-news-published ds sym))

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
    (q/insert-news-batch! ds rows)))

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
  (q/latest-filing-date ds sym))

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
    (q/insert-filings-batch! ds rows)))

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

(defn- current-members [ds u] (q/current-members ds u))

(defn- log-drift! [ds u action sym]
  (let [id (q/next-drift-id ds)]
    (q/insert-drift! ds {:id id :universe u :symbol sym :action action})))

(defn- sync-universe! [ds u tickers]
  (let [prev (current-members ds u)
        next (set tickers)
        added   (clojure.set/difference next prev)
        removed (clojure.set/difference prev next)]
    (q/upsert-universe! ds {:name u :description (str u " (auto)")})
    (doseq [s added]
      (q/add-member!  ds {:universe u :symbol s})
      (log-drift! ds u "added" s))
    (doseq [s removed]
      (q/remove-member! ds {:universe u :symbol s})
      (log-drift! ds u "removed" s))
    {:added (count added) :removed (count removed) :total (count next)}))

(defn refresh-universes!
  "Sync universe memberships into universe_members + write drift log.

   With no :sources opt, refreshes every known source — built-in fetched
   (sp500, nasdaq100) AND any user-defined static universes the user has
   dropped at <config-root>/universes/<name>.edn. Pass :sources to refresh
   a subset (e.g. [:sp500] for just the index, [:my-bench] for just one
   custom file)."
  [{:keys [ds sources] :or {sources (universes/all-source-keys)}}]
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

;;; ── IV / HV daily history ──────────────────────────────────────────────────

(defn- iv-duration-for-gap [last-d]
  (duration-for-gap* last-d "2 Y"))

(defn- ib-reconnect!
  "Drop and reopen the IB API connection with the same credentials. IB
   resets its own 60-per-10-min historical counter per-connection, so
   the reconnect gives us a fresh window. Mirrors that on the client
   side by clearing pacer's hist-global. Throws if reconnect fails."
  [{:keys [host port client-id]}]
  (log/infof "iv-daily: reconnecting to TWS (host=%s port=%s client-id=%s) to reset 60/10-min window"
             host port client-id)
  (try (ibkr/disconnect!) (catch Throwable _))
  (pacer/reset-hist-global!)
  (let [c (ibkr/connect! host port (or client-id 1))]
    (when (= :unavailable c)
      (throw (ex-info "iv-daily: reconnect failed — TWS unavailable"
                      {:host host :port port :client-id client-id})))
    c))

(def ^:private hist-batch-size
  "Cycle the IB connection after this many historical-bar requests so we
   never even brush IB's 60-per-10-min wall. 50 leaves a 10-slot buffer
   for transient sharing with other historical callers; tune lower if
   you see the pacer flag a near-miss before the cycle fires."
  50)

(defn- ib-iv-bars
  "Fetch IV bars for sym, returning the bars vec, :unavailable, or
   ::timeout.

   Strategy: deterministic batching. We keep a counter of historical
   requests on the current connection and proactively disconnect +
   reconnect once it reaches hist-batch-size, before any IB-side rate
   limit fires. The reactive paths (pacer-detected wall, await-batch
   timeout) are kept as a safety net for cases where the counter is
   off (e.g. other code reuses the same conn) — they also reset the
   batch counter so the next cycle starts fresh.

   When :ibkr-config isn't supplied (cron without reconnect creds), the
   old behaviour stands: block on pacer/await-hist-slot!."
  [conn-atom count-atom ibkr-cfg sym duration timeout-ms]
  (cond
    (and ibkr-cfg (>= @count-atom hist-batch-size))
    (do (log/infof "iv-daily: cycling connection after %d requests in this batch"
                   @count-atom)
        (reset! conn-atom (ib-reconnect! ibkr-cfg))
        (reset! count-atom 0))

    (not (pacer/can-request-historical? pacer/default-pacer sym "1 day"))
    (if ibkr-cfg
      (do (log/infof "iv-daily: pacer flagged wall before scheduled cycle — reconnecting now")
          (reset! conn-atom (ib-reconnect! ibkr-cfg))
          (reset! count-atom 0))
      (do (log/infof "iv-daily: %s waiting for IB slot (%d/%d used in 10-min window)"
                     sym (pacer/hist-global-count) 60)
          (pacer/await-hist-slot! pacer/default-pacer sym "1 day"))))
  (swap! count-atom inc)
  (let [result (await-batch
                 (fn [cb]
                   (ibkr/req-historical-bars
                     @conn-atom (ibkr/->contract sym) "1 day" duration
                     :option-implied-volatility cb))
                 timeout-ms
                 (fn [rid] (ibkr/cancel-sub! @conn-atom rid)))]
    (if (and (= ::timeout result) ibkr-cfg)
      (do (log/infof "iv-daily: %s timed out — reconnecting to reset IB's window" sym)
          (reset! conn-atom (ib-reconnect! ibkr-cfg))
          (reset! count-atom 0)
          ::timeout)
      result)))

(defn- bars-close-series [ds sym]
  (->> (jdbc/execute! ds
         ["SELECT bar_date, close FROM bars_daily
            WHERE symbol = ? AND close > 0 ORDER BY bar_date" sym]
         as-lower)
       vec))

(defn- annualised-stddev
  "Sample stddev of log returns × √252. nil when fewer than 2 returns."
  [closes]
  (when (>= (count closes) 2)
    (let [rets (mapv (fn [[a b]] (Math/log (/ (double b) (double a))))
                     (partition 2 1 closes))
          n    (count rets)
          mean (/ (reduce + rets) n)
          var  (/ (reduce + (map (fn [r] (let [d (- r mean)] (* d d))) rets))
                  (max 1 (dec n)))]
      (* (Math/sqrt var) (Math/sqrt 252)))))

(defn- hv30-by-date
  "Compute HV30 (annualised stddev of log returns × √252) per date from
   the input bar rows.

   2-arity form: skip windows whose end-date is on or before `since`
   (any ISO date / LocalDate). Used for routine refreshes so we don't
   re-emit ~1200 unchanged HV30 entries every run — only the gap since
   the last persisted iv_date gets new HV30 values."
  ([rows] (hv30-by-date rows nil))
  ([rows since]
   (let [closes  (mapv :close rows)
         dates   (mapv (comp ->iso-date :bar_date) rows)
         n       (count closes)
         since'  (some-> since str)]
     (->> (range 31 (inc n))
          (keep (fn [i]
                  (let [d (nth dates (dec i))]
                    (when (or (nil? since')
                              (pos? (compare d since')))
                      (when-let [v (annualised-stddev (subvec closes (- i 31) i))]
                        [d v])))))
          (into {})))))

(defn- index-by-date [bars]
  (into {}
        (keep (fn [b]
                (let [d (or (:bar-date b) (:date b) (:time b))]
                  (when (and d (:close b))
                    [(->iso-date d) (:close b)]))))
        bars))

(defn- iv-is-fresh?
  "True when iv_daily's latest row is at least as recent as bars_daily's
   latest row for sym — no new data to add."
  [ds sym]
  (let [bar-d (latest-bar-date ds sym)
        iv-d  (q/latest-iv-date ds sym)]
    (boolean (and bar-d iv-d
                  (or (= bar-d iv-d)
                      (and (instance? LocalDate bar-d)
                           (instance? LocalDate iv-d)
                           (not (.isAfter ^LocalDate bar-d ^LocalDate iv-d))))))))

(defn refresh-iv-daily!
  "Pull daily IV30 from IB and compute HV30 locally from bars_daily,
   merge by date, upsert into iv_daily. Skips symbols where iv_daily is
   already as fresh as bars_daily.

   When :ibkr-config (host/port/client-id) is supplied, the worker
   disconnects and reconnects on hitting IB's 60/10-min window — IB
   resets the counter per-connection, so a reconnect costs ~1s instead
   of a multi-minute wait. Forces parallelism=1 because the reconnect
   would orphan in-flight requests; without ibkr-config it falls back
   to blocking on await-hist-slot!."
  [{:keys [conn ds symbols timeout-ms ibkr-config] :or {timeout-ms 15000}}]
  (with-log ds :iv-daily nil
    (binding [*parallelism* (if ibkr-config 1 (min *parallelism* 4))
              *fail-fast-consecutive* 25]
      (let [conn-atom  (atom conn)
            count-atom (atom 0)]
        (each-symbol! :iv-daily symbols
          (fn [sym]
            (if (iv-is-fresh? ds sym)
              {:rows 0}
              (let [last-d (q/latest-iv-date ds sym)
                    dur    (iv-duration-for-gap last-d)
                    iv     (ib-iv-bars conn-atom count-atom ibkr-config
                                       sym dur timeout-ms)]
              (cond
                (= :unavailable iv) :unavailable
                (= ::timeout    iv) {:timeout? true}
                :else
                (let [iv-by-date (index-by-date iv)
                      ;; First-time backfill (no prior iv_daily rows for sym)
                      ;; → full HV30 history. Routine refresh → only dates
                      ;; after the most recent iv_date we have. Cuts the
                      ;; per-symbol upsert from ~1231 rows to a handful.
                      hv-by-date (hv30-by-date (bars-close-series ds sym)
                                               last-d)
                      dates      (into (sorted-set) (concat (keys iv-by-date)
                                                            (keys hv-by-date)))]
                  (doseq [d dates]
                    (q/upsert-iv-row! ds {:symbol sym :iv-date d
                                           :iv30 (get iv-by-date d)
                                           :hv30 (get hv-by-date d)}))
                  {:rows (count dates)}))))))))))

;;; ── Short interest (Yahoo) ─────────────────────────────────────────────────

(defn refresh-short-interest!
  "Per symbol: pull the latest short-interest snapshot from Yahoo's
   defaultKeyStatistics module and upsert into short_interest. Yahoo
   reports settlement_date so each refresh is naturally deduped on the
   (symbol, settlement_date) PK."
  [{:keys [ds symbols]}]
  (with-log ds :short-interest nil
    (each-symbol! :short-interest symbols
      (fn [sym]
        (if-let [row (yf/fetch-short-interest sym)]
          (if (:settlement-date row)
            (do (q/upsert-short-interest! ds row) {:rows 1})
            nil)
          nil)))))

;;; ── Earnings (Yahoo) ───────────────────────────────────────────────────────

(defn refresh-earnings!
  "Per symbol: pull historical EPS surprises (last ~4 quarters) into
   earnings_events, and the next upcoming earnings date into
   earnings_calendar. Both come from Yahoo via clj-yfinance."
  [{:keys [ds symbols]}]
  (with-log ds :earnings nil
    (each-symbol! :earnings symbols
      (fn [sym]
        (let [hist (yf/fetch-earnings-history sym)
              cal  (yf/fetch-earnings-calendar sym)]
          (doseq [row (or hist [])]
            (q/upsert-earnings-event! ds row))
          (when cal
            (q/upsert-earnings-calendar! ds cal))
          {:rows (+ (count (or hist [])) (if cal 1 0))})))))

;;; ── Ping (smoke test) ──────────────────────────────────────────────────────

(defn ping
  [{:keys [conn ds]}]
  (let [tws? (and conn (ibkr/is-connected?))
        db?  (try (jdbc/execute-one! ds ["SELECT 1 AS n"] as-lower) true
                  (catch Throwable _ false))]
    {:tws-connected? (boolean tws?)
     :db-reachable?  (boolean db?)
     :ok?            (and tws? db?)}))
