(ns options-trader.indicators.engine
  "Indicator computation engine: loads bars, runs ta4j indicators,
   upserts results into latest_indicators (wide format, one row per symbol)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.data.fundamentals :as fundamentals]
            [options-trader.indicators.beta :as beta]
            [options-trader.indicators.composites :as composites]
            [options-trader.indicators.earnings-views :as earnings-views]
            [options-trader.indicators.event-flags :as event-flags]
            [options-trader.indicators.fundamentals-views :as fundamentals-views]
            [options-trader.indicators.iv :as iv]
            [options-trader.indicators.option-chain-agg :as oca]
            [options-trader.indicators.price-action-views :as price-action-views]
            [options-trader.indicators.runner :as runner]
            [options-trader.indicators.sector-metrics :as sector-metrics]
            [options-trader.indicators.ta4j :as ta4j]
            [options-trader.paths :as paths])
  (:import [java.time ZoneOffset]))

(defn load-config
  "Read indicators config as EDN. Prefers the user-writable file at
   paths/indicators-file; falls back to the packaged classpath resource
   when the user file is absent (tests, fresh checkouts before init)."
  []
  (let [user (io/file (paths/indicators-file))]
    (edn/read-string
      (slurp (if (.exists user) user (io/resource "indicators.edn"))))))

;;; ── Bar loading ─────────────────────────────────────────────────────────────

(defn- local-date->epoch-ms [d]
  (-> d
      (.atStartOfDay ZoneOffset/UTC)
      .toInstant
      .toEpochMilli))

(defn load-bars
  "Load bars_daily rows for symbol from ds, sorted ascending by date.
   Returns maps with :bar_date (LocalDate), :time (epoch-ms), :open, :high, :low, :close, :volume."
  [ds symbol]
  (let [rows (jdbc/execute! ds
               ["SELECT bar_date, open, high, low, close, volume
                 FROM bars_daily WHERE symbol = ? ORDER BY bar_date ASC"
                symbol]
               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [r]
            {:bar_date (:bar_date r)
             :time     (local-date->epoch-ms (:bar_date r))
             :open     (double (or (:open r) 0.0))
             :high     (double (or (:high r) 0.0))
             :low      (double (or (:low r) 0.0))
             :close    (double (or (:close r) 0.0))
             :volume   (long   (or (:volume r) 0))})
          rows)))

;;; ── Indicator construction ──────────────────────────────────────────────────

(def ^:private indicator-builders
  "Map of indicator :kind keyword to a fn taking [series params] and returning
   the ta4j indicator object. Eliminates the large case dispatch."
  {:RSI                   #(ta4j/rsi                 %1 (first %2))
   :SMA                   #(ta4j/sma                 %1 (first %2))
   :EMA                   #(ta4j/ema                 %1 (first %2))
   :ATR                   #(ta4j/atr                 %1 (first %2))
   :BollingerBandWidth    #(ta4j/bollinger-band-width %1 (first %2) (second %2))
   :ADX                   #(ta4j/adx                 %1 (first %2))
   :DX                    #(ta4j/dx                  %1 (first %2))
   :PlusDI                #(ta4j/plus-di             %1 (first %2))
   :MinusDI               #(ta4j/minus-di            %1 (first %2))
   :AroonUp               #(ta4j/aroon-up            %1 (first %2))
   :AroonDown             #(ta4j/aroon-down          %1 (first %2))
   :AroonOsc              #(ta4j/aroon-osc           %1 (first %2))
   :Chop                  #(ta4j/chop                %1 (first %2))
   :StochasticOscillatorK #(ta4j/stoch-k             %1 (first %2))
   :StochasticOscillatorD #(ta4j/stoch-d             %1 (first %2))
   :StochasticRSI         #(ta4j/stoch-rsi           %1 (first %2))
   :WilliamsR             #(ta4j/williams-r          %1 (first %2))
   :CCI                   #(ta4j/cci                 %1 (first %2))
   :CMO                   #(ta4j/cmo                 %1 (first %2))
   :MACD                  #(ta4j/macd                %1 (first %2) (second %2))
   :MACDSignal            #(ta4j/macd-signal         %1 (first %2) (second %2) (nth %2 2))
   :MACDHist              #(ta4j/macd-hist           %1 (first %2) (second %2) (nth %2 2))
   :STDDEV                #(ta4j/stddev              %1 (first %2))
   :ParabolicSAR          #(ta4j/parabolic-sar       %1 (first %2) (second %2) (nth %2 2))
   :OBV                   (fn [s _] (ta4j/obv s))
   :UlcerIndex            #(ta4j/ulcer-index         %1 (first %2))
   :MassIndex             #(ta4j/mass-index          %1 (first %2) (second %2))
   :CMF                   #(ta4j/cmf                 %1 (first %2))
   :BBUpper               #(ta4j/bollinger-upper     %1 (first %2) (second %2))
   :BBLower               #(ta4j/bollinger-lower     %1 (first %2) (second %2))
   :PercentB              #(ta4j/percent-b           %1 (first %2) (second %2))
   :KCUpper               #(ta4j/keltner-upper       %1 (first %2) (second %2) (nth %2 2))
   :KCLower               #(ta4j/keltner-lower       %1 (first %2) (second %2) (nth %2 2))
   :Fisher                #(ta4j/fisher              %1 (first %2))
   :PivotPoint            (fn [s _] (ta4j/pivot-point s))
   :PivotR1               (fn [s _] (ta4j/pivot-reversal s :R1))
   :PivotR2               (fn [s _] (ta4j/pivot-reversal s :R2))
   :PivotS1               (fn [s _] (ta4j/pivot-reversal s :S1))
   :PivotS2               (fn [s _] (ta4j/pivot-reversal s :S2))
   :FibR38                (fn [s _] (ta4j/pivot-fib-reversal s 0.382 :resistance))
   :FibR62                (fn [s _] (ta4j/pivot-fib-reversal s 0.618 :resistance))
   :FibS38                (fn [s _] (ta4j/pivot-fib-reversal s 0.382 :support))
   :FibS62                (fn [s _] (ta4j/pivot-fib-reversal s 0.618 :support))})

(defn- build-indicator
  "Build a ta4j indicator object for spec. Returns the indicator (not its value)."
  [series spec]
  (let [{:keys [kind params]} spec
        builder (get indicator-builders kind)]
    (if builder
      (builder series params)
      (throw (ex-info (str "Unknown indicator kind: " (name kind))
                      {:kind kind})))))

;;; ── Indicator dispatch ──────────────────────────────────────────────────────

(defn compute-one
  "Compute the last value of indicator-spec on a ta4j BarSeries.
   spec must have :kind (keyword) and :params (vector of numbers)."
  [series spec]
  (ta4j/indicator-value (build-indicator series spec) (dec (ta4j/bar-count series))))

;;; ── Composite computation ───────────────────────────────────────────────────

;;; ── Cross-direction detection ────────────────────────────────────────────────

(defn- cross-direction
  "Given prev value and curr value, return :bullish if crossing above zero,
   :bearish if crossing below zero, or :none."
  [prev curr]
  (cond
    (and (neg? prev) (>= curr 0.0)) "bullish"
    (and (pos? prev) (<= curr 0.0)) "bearish"
    :else "none"))

(defn- sma-of-values [vals n-days]
  (/ (apply + vals) n-days))

;;; ── Composite computations ──────────────────────────────────────────────────

(defn- compute-ttm-squeeze [values]
  (let [{:keys [bb_upper bb_lower kc_upper kc_lower]} values]
    (boolean (and (some? bb_upper) (some? kc_upper)
                  (< bb_upper kc_upper)
                  (> bb_lower kc_lower)))))

(defn- compute-macd-cross [ind-objs series]
  (let [n (ta4j/bar-count series)]
    (if (< n 2)
      "none"
      (let [macd-ind  (get ind-objs :macd)
            sig-ind   (get ind-objs :macd_signal)
            last-i    (dec n)
            prev-i    (- n 2)
            curr-diff (- (ta4j/indicator-value macd-ind last-i)
                         (ta4j/indicator-value sig-ind  last-i))
            prev-diff (- (ta4j/indicator-value macd-ind prev-i)
                         (ta4j/indicator-value sig-ind  prev-i))]
        (cross-direction prev-diff curr-diff)))))

(defn- compute-mass-reversal [ind-objs series]
  (let [mass-ind  (get ind-objs :mass_index)
        n         (ta4j/bar-count series)
        lookback  (min 15 n)
        vals      (mapv #(ta4j/indicator-value mass-ind %)
                        (range (- n lookback) n))
        latest    (last vals)
        had-bulge (some #(> % 27.0) (butlast vals))]
    (boolean (and had-bulge (some? latest) (< latest 26.5)))))

(defn- compute-psar-flip [ind-objs series bars]
  (let [n (ta4j/bar-count series)]
    (if (< n 2)
      "none"
      (let [psar-ind   (get ind-objs :psar)
            last-i     (dec n)
            prev-i     (- n 2)
            close-curr (:close (nth bars last-i))
            close-prev (:close (nth bars prev-i))
            psar-curr  (ta4j/indicator-value psar-ind last-i)
            psar-prev  (ta4j/indicator-value psar-ind prev-i)]
        (cross-direction (- close-prev psar-prev)
                         (- close-curr psar-curr))))))

(defn- compute-obv-trend [ind-objs series]
  (let [obv-ind (get ind-objs :obv)
        n       (ta4j/bar-count series)
        n50     (max 1 (min 50 n))
        n20     (max 1 (min 20 n))
        start50 (- n n50)
        vals50  (mapv #(ta4j/indicator-value obv-ind %) (range start50 n))
        vals20  (take-last n20 vals50)
        sma50   (sma-of-values vals50 n50)
        sma20   (sma-of-values vals20 n20)]
    (cond
      (> sma20 sma50) "rising"
      (< sma20 sma50) "falling"
      :else "flat")))

(def ^:private composite-computers
  {:ttm-squeeze   (fn [& {:keys [values]}]
                    (compute-ttm-squeeze values))
   :macd-cross    (fn [& {:keys [ind-objs series]}]
                    (compute-macd-cross ind-objs series))
   :mass-reversal (fn [& {:keys [ind-objs series]}]
                    (compute-mass-reversal ind-objs series))
   :psar-flip     (fn [& {:keys [ind-objs series bars]}]
                    (compute-psar-flip ind-objs series bars))
   :obv-trend     (fn [& {:keys [ind-objs series]}]
                    (compute-obv-trend ind-objs series))})

(defn compute-composite
  "Compute a composite flag value from already-built base indicator objects.
   values   – {:col-kw -> double} last value of each base indicator
   ind-objs – {:col-kw -> ta4j-indicator} indicator objects keyed by column
   series   – ta4j BarSeries (for bar count and history extraction)
   bars     – seq of bar maps with :close (from load-bars)
   Returns Boolean for *_flag composites, String for string-valued composites."
  [spec values ind-objs series bars]
  (if-let [computer (get composite-computers (:kind spec))]
    (computer :values values :ind-objs ind-objs :series series :bars bars)
    (throw (ex-info (str "Unknown composite kind: " (name (:kind spec)))
                    {:kind (:kind spec)}))))

;;; ── Schema helpers ──────────────────────────────────────────────────────────

(defn- ensure-column!
  "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS <col> DOUBLE."
  [ds col-kw]
  (jdbc/execute! ds
    [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS "
          (name col-kw) " DOUBLE")]))

(defn- ensure-composite-column!
  "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS <col> BOOLEAN|VARCHAR."
  [ds spec]
  (let [col      (:column spec)
        sql-type (case (:kind spec)
                   (:ttm-squeeze :mass-reversal) "BOOLEAN"
                   "VARCHAR")]
    (jdbc/execute! ds
      [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS "
            (name col) " " sql-type)])))

(defn- upsert-row!
  "INSERT one wide row keyed by symbol; ON CONFLICT (symbol) DO UPDATE."
  [ds symbol values]
  (let [col-names  (mapv name (keys values))
        col-vals   (vec (vals values))
        set-clause (str/join ", " (map #(str % " = excluded." %) col-names))
        sql (str "INSERT INTO latest_indicators (symbol, "
                 (str/join ", " col-names)
                 ") VALUES (?, "
                 (str/join ", " (repeat (count col-names) "?"))
                 ") ON CONFLICT (symbol) DO UPDATE SET "
                 set-clause)]
    (jdbc/execute! ds (into [sql symbol] col-vals))))

;;; ── History persistence ─────────────────────────────────────────────────────

(defn- insert-history!
  "Insert one indicator value into indicator_history; ignore duplicate (symbol, indicator, ind_date)."
  [ds symbol indicator-key ind-date value]
  (jdbc/execute! ds
    ["INSERT INTO indicator_history (symbol, indicator, ind_date, value) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING"
     symbol indicator-key ind-date value]))

;;; ── Percentile rank ─────────────────────────────────────────────────────────

(defn- compute-percentile!
  "PERCENT_RANK of the latest value for indicator over trailing n-days window in DuckDB.
   Returns nil when no history rows exist."
  [ds symbol indicator-key n-days]
  (let [sql (str "WITH w AS ("
                 "  SELECT value, ind_date,"
                 "         PERCENT_RANK() OVER (ORDER BY value) AS pr"
                 "  FROM indicator_history"
                 "  WHERE symbol = ? AND indicator = ?"
                 "    AND ind_date >= ("
                 "      SELECT MAX(ind_date) - INTERVAL '" n-days " days'"
                 "      FROM indicator_history WHERE symbol = ? AND indicator = ?"
                 "    )"
                 ") SELECT pr FROM w ORDER BY ind_date DESC LIMIT 1")
        rows (jdbc/execute! ds [sql symbol indicator-key symbol indicator-key]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (:pr (first rows))))

;;; ── Public entry point ──────────────────────────────────────────────────────

(defn compute-many
  "Compute all indicators from resources/indicators.edn for symbol and upsert
   into latest_indicators, adding missing columns as needed.
   Computes base :indicators first (with history/percentile), then :composites."
  [ds symbol]
  (let [cfg        (load-config)
        specs      (:indicators cfg)
        comp-specs (:composites cfg)
        bars       (load-bars ds symbol)
        series     (ta4j/ds->ta4j-ohlcv bars)
        n          (ta4j/bar-count series)
        last-date  (:bar_date (last bars))]
    ;; Ensure base indicator columns exist
    (doseq [spec specs]
      (ensure-column! ds (:column spec)))
    ;; Build indicator objects and extract last values
    (let [ind-objs (into {} (map (fn [spec]
                                   [(:column spec) (build-indicator series spec)])
                                 specs))
          values   (into {} (map (fn [[col ind]]
                                   [col (ta4j/indicator-value ind (dec n))])
                                 ind-objs))]
      ;; Record history for each base indicator
      (doseq [[col v] values]
        (insert-history! ds symbol (name col) last-date v))
      ;; Upsert all base indicator values
      (upsert-row! ds symbol values)
      ;; Ensure composite columns exist with appropriate SQL types
      (doseq [spec comp-specs]
        (ensure-composite-column! ds spec))
      ;; Compute and upsert composite values
      (when (seq comp-specs)
        (let [comp-values (into {}
                               (keep (fn [spec]
                                       (let [v (compute-composite spec values ind-objs series bars)]
                                         (when (some? v) [(:column spec) v])))
                                     comp-specs))]
          (when (seq comp-values)
            (upsert-row! ds symbol comp-values))))
      ;; Compute percentile ranks for configured base indicators
      (doseq [spec   specs
              :let   [windows (:percentile-windows spec)]
              :when  windows
              n-days windows]
        (let [col     (:column spec)
              ind-key (name col)
              pct-col (keyword (str ind-key "_percentile_" n-days "d"))
              pct-val (compute-percentile! ds symbol ind-key n-days)]
          (when (some? pct-val)
            (ensure-column! ds pct-col)
            (upsert-row! ds symbol {pct-col pct-val})))))))

(defn refresh-derived-indicators!
  "Run all derived indicator passes.  runner/refresh-runner-indicators! fires first
   to populate persistent ta4j columns before any SQL-only slice can COALESCE
   against them.  SQL-based slices follow in dependency order.

   Note: fundamentals are NOT refreshed here — they hit SEC EDGAR and take ~1
   request per symbol, which is too slow for interactive cadence. Run
   `refresh-fundamentals!` separately on a daily schedule."
  [ds]
  (runner/refresh-runner-indicators! ds)
  (iv/refresh-iv-indicators! ds)
  (oca/refresh-option-chain-agg! ds)
  (sector-metrics/refresh-sector-metrics! ds)
  (earnings-views/refresh-earnings-views! ds)
  (price-action-views/refresh-price-action-views! ds)
  (event-flags/refresh-event-flags! ds)
  (beta/refresh-beta! ds)
  (composites/refresh-composites! ds))

(defn refresh-fundamentals!
  "Fetch fundamentals from SEC EDGAR and persist + upsert ratio columns.
   Slow — daily-cadence job. With opts {:symbols [...]} scope to the given
   tickers; otherwise walks every symbol in bars_daily."
  ([ds] (refresh-fundamentals! ds {}))
  ([ds opts]
   (fundamentals-views/refresh-fundamentals-views!
     ds
     (fundamentals/make-source {:type :edgar})
     opts)))
