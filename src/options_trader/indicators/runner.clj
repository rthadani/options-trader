(ns options-trader.indicators.runner
  "ta4j-driven indicator runner: reads resources/indicators.edn, computes per-symbol
   latest values for all declared single-series indicators, and upserts into
   latest_indicators.  Called at the top of engine/refresh-derived-indicators! so
   persistent ta4j columns are populated before any SQL-only slice can COALESCE
   against them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [options-trader.db.queries.indicators :as q]
            [options-trader.indicators.ta4j :as ta4j]
            [taoensso.timbre :as log])
  (:import [java.time ZoneOffset]))

;;; ── Bar loading ─────────────────────────────────────────────────────────────

(defn- local-date->epoch-ms [d]
  (-> d (.atStartOfDay ZoneOffset/UTC) .toInstant .toEpochMilli))

(defn- load-bars
  "Load OHLCV rows for sym from bars_daily, sorted ascending by bar_date."
  [ds sym]
  (mapv (fn [r]
          {:bar_date (:bar_date r)
           :time     (local-date->epoch-ms (:bar_date r))
           :open     (double (or (:open r) 0.0))
           :high     (double (or (:high r) 0.0))
           :low      (double (or (:low r) 0.0))
           :close    (double (or (:close r) 0.0))
           :volume   (long   (or (:volume r) 0))})
        (q/load-bars-daily ds sym)))

(defn- all-symbols [ds] (q/all-bars-daily-symbols ds))

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
    (q/ensure-double-columns! ds (mapv :column (:indicators cfg)))))

(defn- upsert-row! [ds sym values]
  (q/upsert-latest-row! ds sym values))

;;; ── Public API ──────────────────────────────────────────────────────────────

(defn- compute-symbol-values
  "Pure compute step: load bars + run every spec, return [sym values] or nil
   when the symbol has no bars. Safe to run concurrently — only does DuckDB
   reads (which are MVCC-safe) and CPU-bound ta4j work."
  [ds specs sym]
  (let [bars (load-bars ds sym)]
    (when (pos? (count bars))
      (let [series (ta4j/ds->ta4j-ohlcv bars)
            values (into {}
                         (keep (fn [spec]
                                 (when-let [v (compute-spec-value series spec)]
                                   [(:column spec) v])))
                         specs)]
        [sym values]))))

(defn refresh-runner-indicators!
  "For every distinct symbol in bars_daily: load OHLCV, build a ta4j
   BaseBarSeries, instantiate each indicator declared in indicators.edn,
   take the last value, and upsert into latest_indicators keyed on
   (symbol).

   Compute is parallelised via pmap (DuckDB reads + ta4j are thread-safe,
   independent per symbol). Upserts run sequentially because DuckDB has
   one writer.

   Progress is logged every 10% of the symbol list, plus a wall-clock
   timing line at the end.

   Per-indicator skips:
   - Unknown :kind → warning logged, column omitted from row.
   - Insufficient bars / NaN / Infinite → column omitted silently.
   Row skipped entirely when no value is computable, so NULLs aren't written."
  [ds]
  (ensure-schema! ds)
  (let [cfg    (edn/read-string (slurp (io/resource "indicators.edn")))
        specs  (:indicators cfg)
        syms   (all-symbols ds)
        total  (count syms)
        step   (max 1 (long (/ total 10)))
        t0     (System/currentTimeMillis)
        done   (atom 0)
        log-progress!
        (fn [sym]
          (let [n (swap! done inc)]
            (when (or (zero? (mod n step)) (= n total))
              (let [dt (/ (- (System/currentTimeMillis) t0) 1000.0)
                    pct (long (* 100.0 (/ n (double total))))]
                (log/infof "runner: %d/%d symbols (%d%%) in %.1fs — last %s"
                           n total pct dt sym)))))]
    (log/infof "runner: computing %d indicators across %d symbols (parallel)"
               (count specs) total)
    (doseq [result (pmap #(compute-symbol-values ds specs %) syms)]
      (when result
        (let [[sym values] result]
          (upsert-row! ds sym values)
          (log-progress! sym))))
    (log/infof "runner: done — %d symbols in %.1fs"
               total (/ (- (System/currentTimeMillis) t0) 1000.0))))
