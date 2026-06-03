(ns options-trader.indicators.fundamentals-views
  "EDGAR-backed fundamentals surfaced as columns on latest_indicators, with
   the full canonical map persisted to the fundamentals table.

   For each symbol in bars_daily: fetch + normalise via the configured EDGAR
   fundamentals source, join against the latest close from bars_daily for
   ratios that need price (P/E, FCF yield), then upsert the ratio columns
   onto latest_indicators. Symbols not in EDGAR (foreign ADRs, most ETFs)
   are skipped with a warning."
  (:require [clojure.string                   :as str]
            [cheshire.core                    :as json]
            [next.jdbc                        :as jdbc]
            [next.jdbc.result-set             :as rs]
            [taoensso.timbre                  :as log]
            [options-trader.data.fundamentals :as fund]))

;;; ── Schema ──────────────────────────────────────────────────────────────────

(def ^:private columns
  [["pe_ratio"            "DOUBLE"]
   ["earnings_yield"      "DOUBLE"]
   ["price_to_book"       "DOUBLE"]
   ["fcf_yield"           "DOUBLE"]
   ["debt_to_equity"      "DOUBLE"]
   ["current_ratio"       "DOUBLE"]
   ["gross_margin"        "DOUBLE"]
   ["operating_margin"    "DOUBLE"]
   ["net_margin"          "DOUBLE"]
   ["roe"                 "DOUBLE"]
   ["revenue_growth_yoy"  "DOUBLE"]
   ["net_income_growth_yoy" "DOUBLE"]
   ["eps_growth_yoy"      "DOUBLE"]])

(defn ensure-schema! [ds]
  (doseq [[col t] columns]
    (jdbc/execute! ds
      [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS " col " " t)])))

;;; ── Helpers ─────────────────────────────────────────────────────────────────

(defn- div [a b]
  (when (and (number? a) (number? b) (not (zero? b)))
    (double (/ a b))))

(defn- growth [curr prev]
  (when (and (number? curr) (number? prev) (not (zero? prev)))
    (double (/ (- curr prev) prev))))

(defn- ratios
  "Compute the screenable ratios from a canonical fundamentals map +
   the latest close + shares-outstanding-derived market-cap."
  [{:keys [revenue gross-profit operating-income net-income eps-diluted
           total-liabilities current-liabilities current-assets
           long-term-debt stockholders-equity free-cash-flow
           shares-diluted history] :as _f}
   close]
  (let [market-cap (when (and (number? close) (number? shares-diluted))
                     (* close shares-diluted))
        book-value (div stockholders-equity shares-diluted)
        prior      (first history)]
    {:pe_ratio              (div close eps-diluted)
     :earnings_yield        (div eps-diluted close)
     :price_to_book         (div close book-value)
     :fcf_yield             (div free-cash-flow market-cap)
     :debt_to_equity        (div long-term-debt stockholders-equity)
     :current_ratio         (div current-assets current-liabilities)
     :gross_margin          (div gross-profit revenue)
     :operating_margin      (div operating-income revenue)
     :net_margin            (div net-income revenue)
     :roe                   (div net-income stockholders-equity)
     :revenue_growth_yoy    (growth revenue       (:revenue       prior))
     :net_income_growth_yoy (growth net-income    (:net-income    prior))
     :eps_growth_yoy        (growth eps-diluted   (:eps-diluted   prior))}))

;;; ── DB I/O ──────────────────────────────────────────────────────────────────

(defn- all-symbols [ds]
  (->> (jdbc/execute! ds
         ["SELECT DISTINCT symbol FROM bars_daily ORDER BY symbol"]
         {:builder-fn rs/as-unqualified-lower-maps})
       (map :symbol)))

(defn- latest-close [ds sym]
  (-> (jdbc/execute-one! ds
        ["SELECT close FROM bars_daily WHERE symbol = ?
            ORDER BY bar_date DESC LIMIT 1" sym]
        {:builder-fn rs/as-unqualified-lower-maps})
      :close))

(defn- persist-fundamentals! [ds sym period payload]
  (jdbc/execute! ds
    ["INSERT INTO fundamentals (symbol, period, fetched_at, data)
        VALUES (?, ?, current_timestamp, ?)
      ON CONFLICT (symbol, period) DO UPDATE SET
        fetched_at = excluded.fetched_at,
        data       = excluded.data"
     sym period (json/generate-string payload)]))

(defn- upsert-row! [ds sym values]
  (let [present    (into {} (remove (comp nil? val) values))
        cols       (mapv name (keys present))
        vals       (vec (vals present))
        set-clause (str/join ", " (map #(str % " = excluded." %) cols))
        sql        (str "INSERT INTO latest_indicators (symbol, "
                        (str/join ", " cols)
                        ") VALUES (?, "
                        (str/join ", " (repeat (count cols) "?"))
                        ") ON CONFLICT (symbol) DO UPDATE SET "
                        set-clause)]
    (when (seq cols)
      (jdbc/execute! ds (into [sql sym] vals)))))

;;; ── Public entry point ──────────────────────────────────────────────────────

(defn refresh-fundamentals-views!
  "Fetch fundamentals for symbols via the EDGAR source, persist the canonical
   payload to the fundamentals table, and upsert computed ratio columns onto
   latest_indicators.

   With no :symbols opt, walks every distinct symbol in bars_daily. Pass
   :symbols [\"XEL\" \"XYL\" ...] to scope the run — useful for retrying a
   partial failure or smoke-testing a single ticker.

   Slow path: ~1 request per symbol (edgarjure rate-limits to ~10 req/sec
   internally). Intended for daily-cadence refresh. Symbols not in EDGAR
   are logged and skipped."
  ([ds]
   (refresh-fundamentals-views! ds (fund/make-source {:type :edgar}) {}))
  ([ds source]
   (refresh-fundamentals-views! ds source {}))
  ([ds source {:keys [symbols]}]
   (ensure-schema! ds)
   (let [syms        (or (seq symbols) (all-symbols ds))
         total       (count syms)
         oom-cap     5
         step!
         (fn [{:keys [oom-streak] :as tally} [i sym]]
           (when (>= oom-streak oom-cap)
             (throw (ex-info (str "aborting fundamentals: " oom-streak
                                  " consecutive OOMs — heap is wedged, bump -Xmx")
                             (assoc tally :aborted-at i :total total))))
           (try
             (let [p   (promise)
                   _   (fund/fetch-fundamentals sym {} source
                         (fn [evs] (deliver p evs)))
                   evs (deref p 30000 nil)
                   raw (first evs)]
               (cond
                 (or (nil? raw) (= :error (:type raw)))
                 (do (log/infof "fundamentals [%d/%d] %s skipped (%s)"
                                (inc i) total sym (:message raw))
                     (-> tally (update :skipped inc) (assoc :oom-streak 0)))

                 :else
                 (let [norm  (fund/normalise-fundamentals raw source)
                       close (latest-close ds sym)
                       rs    (ratios norm close)]
                   (persist-fundamentals! ds sym (str (:as-of norm)) norm)
                   (upsert-row! ds sym rs)
                   (log/infof "fundamentals [%d/%d] %s ok (as-of %s)"
                              (inc i) total sym (:as-of norm))
                   (-> tally (update :ok inc) (assoc :oom-streak 0)))))
             (catch OutOfMemoryError t
               (log/warnf "fundamentals [%d/%d] %s OOM — heap wedged" (inc i) total sym)
               (System/gc)
               (-> tally (update :failed inc) (update :oom-streak inc)))
             (catch Throwable t
               (log/warnf "fundamentals [%d/%d] %s failed: %s"
                          (inc i) total sym (.getMessage t))
               (-> tally (update :failed inc) (assoc :oom-streak 0)))))]
     (log/infof "fundamentals: processing %d symbols" total)
     (-> (reduce step! {:ok 0 :skipped 0 :failed 0 :oom-streak 0}
                 (map-indexed vector syms))
         (dissoc :oom-streak)
         (assoc :total total)))))
