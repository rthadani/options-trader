(ns options-trader.indicators.runner
  "ta4j-driven indicator runner: reads resources/indicators.edn, computes per-symbol
   latest values for all declared single-series indicators, and upserts into
   latest_indicators.  Called at the top of engine/refresh-derived-indicators! so
   persistent ta4j columns are populated before any SQL-only slice can COALESCE
   against them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.indicators.ta4j :as ta4j]
            [taoensso.timbre :as log])
  (:import [java.time ZoneOffset]))

;;; ── Bar loading ─────────────────────────────────────────────────────────────

(defn- local-date->epoch-ms [d]
  (-> d (.atStartOfDay ZoneOffset/UTC) .toInstant .toEpochMilli))

(defn- load-bars
  "Load OHLCV rows for sym from bars_daily, sorted ascending by bar_date."
  [ds sym]
  (let [rows (jdbc/execute! ds
               ["SELECT bar_date, open, high, low, close, volume
                 FROM bars_daily WHERE symbol = ? ORDER BY bar_date ASC"
                sym]
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

(defn- all-symbols
  "Return all distinct symbols present in bars_daily."
  [ds]
  (->> (jdbc/execute! ds
         ["SELECT DISTINCT symbol FROM bars_daily ORDER BY symbol"]
         {:builder-fn rs/as-unqualified-lower-maps})
       (mapv :symbol)))

;;; ── Indicator computation ───────────────────────────────────────────────────

(defn- safe-last-value
  "Extract the double value at the last bar index; returns nil on NaN/Infinite/error."
  [ind n]
  (try
    (let [v (ta4j/indicator-value ind (dec n))]
      (when (and (some? v)
                 (not (Double/isNaN v))
                 (not (Double/isInfinite v)))
        v))
    (catch Exception _ nil)))

(defn- compute-spec-value
  "Instantiate indicator for spec on series and return its last value, or nil.
   Logs a warning when the :kind is unregistered in ta4j/indicator-dispatch."
  [series spec]
  (let [{:keys [kind params column]} spec]
    (try
      (let [ind (ta4j/indicator kind series params)]
        (when (nil? ind)
          (log/warn "runner: unresolved indicator kind" kind "— skipping column" column))
        (when ind
          (safe-last-value ind (ta4j/bar-count series))))
      (catch Exception e
        (log/warn "runner: error computing" kind "column" column "-" (.getMessage e))
        nil))))

;;; ── Schema management ───────────────────────────────────────────────────────

(defn ensure-schema!
  "Idempotently add a DOUBLE column to latest_indicators for every indicator
   declared in resources/indicators.edn.  Safe to call multiple times."
  [ds]
  (let [cfg (edn/read-string (slurp (io/resource "indicators.edn")))]
    (doseq [spec (:indicators cfg)]
      (jdbc/execute! ds
        [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS "
              (name (:column spec)) " DOUBLE")]))))

;;; ── Upsert ──────────────────────────────────────────────────────────────────

(defn- upsert-row!
  "INSERT one wide row keyed on symbol; ON CONFLICT (symbol) DO UPDATE SET each col."
  [ds sym values]
  (when (seq values)
    (let [cols       (mapv name (keys values))
          vals       (vec (vals values))
          set-clause (str/join ", " (map #(str % " = excluded." %) cols))
          sql        (str "INSERT INTO latest_indicators (symbol, "
                          (str/join ", " cols)
                          ") VALUES (?, "
                          (str/join ", " (repeat (count cols) "?"))
                          ") ON CONFLICT (symbol) DO UPDATE SET "
                          set-clause)]
      (jdbc/execute! ds (into [sql sym] vals)))))

;;; ── Public API ──────────────────────────────────────────────────────────────

(defn refresh-runner-indicators!
  "For every distinct symbol in bars_daily: load OHLCV via next.jdbc, build a
   ta4j BaseBarSeries via ds->ta4j-ohlcv, instantiate each indicator declared in
   resources/indicators.edn via ta4j/indicator, take the last value, and upsert
   into latest_indicators keyed on (symbol).

   Per-indicator skips:
   - Unknown :kind → warning logged, column omitted from row.
   - Insufficient bars / NaN / Infinite → column omitted silently.

   The row for a symbol is skipped entirely when no indicator value is computable
   (e.g. zero bars loaded), so NULLs are never written."
  [ds]
  (ensure-schema! ds)
  (let [cfg   (edn/read-string (slurp (io/resource "indicators.edn")))
        specs (:indicators cfg)
        syms  (all-symbols ds)]
    (doseq [sym syms]
      (let [bars (load-bars ds sym)
            n    (count bars)]
        (when (pos? n)
          (let [series (ta4j/ds->ta4j-ohlcv bars)
                values (into {}
                             (keep (fn [spec]
                                     (when-let [v (compute-spec-value series spec)]
                                       [(:column spec) v]))
                                   specs))]
            (upsert-row! ds sym values)))))))
