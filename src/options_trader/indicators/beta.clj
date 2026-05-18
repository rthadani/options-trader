(ns options-trader.indicators.beta
  "Cross-symbol regression slice: computes 252-day OLS beta of each symbol's daily
   log returns against a benchmark (default SPY) via DuckDB regr_slope.
   Benchmark symbol is read from resources/config.edn at [:indicators :beta-benchmark];
   falls back to 'SPY'. Results are upserted into latest_indicators as beta_252d DOUBLE.
   Symbols with fewer than 252 paired trading-day observations receive NULL."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]))

;;; ── Schema ───────────────────────────────────────────────────────────────────

(defn ensure-schema!
  "Add beta_252d DOUBLE to latest_indicators if absent. Idempotent via IF NOT EXISTS."
  [ds]
  (jdbc/execute! ds
    ["ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS beta_252d DOUBLE"]))

;;; ── Config ───────────────────────────────────────────────────────────────────

(defn- benchmark-symbol
  "Read :indicators :beta-benchmark from config.edn (via aero so #env/#or
   tags resolve), defaulting to 'SPY'."
  []
  (try
    (let [cfg (aero/read-config (io/resource "config.edn") {:profile :dev})]
      (or (get-in cfg [:indicators :beta-benchmark])
          (get-in cfg [:dev :indicators :beta-benchmark])
          "SPY"))
    (catch Throwable _ "SPY")))

;;; ── SQL ──────────────────────────────────────────────────────────────────────
;;
;; Single INSERT…SELECT that computes and upserts beta in one DuckDB round-trip.
;;
;; CTE 1 – sym_log_returns: derives per-symbol daily log returns from bars_daily
;;          using LAG over (symbol, bar_date). Rows without a prior close are NULL
;;          and excluded downstream.
;;
;; CTE 2 – bench: filters benchmark_returns to the chosen benchmark symbol,
;;          aliasing ret_1d as bench_ret.
;;
;; CTE 3 – paired: inner-joins the two on bar_date = ret_date, keeping only
;;          rows where the symbol's log return is non-NULL.
;;
;; CTE 4 – ranked: assigns a recency rank (rn=1 = most recent) per symbol so
;;          the trailing-252 window can be sliced with a simple WHERE rn <= 252.
;;
;; CTE 5 – betas: aggregates per symbol, using COUNT(*) >= 252 as guard; symbols
;;          with insufficient history receive NULL rather than a fabricated value.
;;
;; The final INSERT uses ON CONFLICT (symbol) DO UPDATE for idempotent upsert.

(def ^:private beta-sql
  "INSERT INTO latest_indicators (symbol, beta_252d)
   WITH
     sym_log_returns AS (
       SELECT
         symbol,
         bar_date,
         LN(close / LAG(close) OVER (PARTITION BY symbol ORDER BY bar_date)) AS log_ret
       FROM bars_daily
       WHERE close > 0
     ),
     bench AS (
       SELECT
         ret_date,
         ret_1d AS bench_ret
       FROM benchmark_returns
       WHERE benchmark = ?
         AND ret_1d IS NOT NULL
     ),
     paired AS (
       SELECT
         s.symbol,
         s.bar_date,
         s.log_ret,
         b.bench_ret
       FROM sym_log_returns s
       JOIN bench b ON s.bar_date = b.ret_date
       WHERE s.log_ret IS NOT NULL
     ),
     ranked AS (
       SELECT
         symbol,
         bar_date,
         log_ret,
         bench_ret,
         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY bar_date DESC) AS rn
       FROM paired
     ),
     betas AS (
       SELECT
         symbol,
         CASE WHEN COUNT(*) >= 252
              THEN regr_slope(log_ret, bench_ret)
              ELSE NULL
         END AS beta_252d
       FROM ranked
       WHERE rn <= 252
       GROUP BY symbol
     )
   SELECT symbol, beta_252d FROM betas
   ON CONFLICT (symbol) DO UPDATE SET beta_252d = excluded.beta_252d")

;;; ── Public entry point ───────────────────────────────────────────────────────

(defn refresh-beta!
  "Compute 252-day OLS beta for every symbol in bars_daily against benchmark-sym
   and upsert into latest_indicators. Requires >= 252 paired daily observations
   between symbol log returns and benchmark_returns; NULL otherwise.
   Called with one argument, reads the benchmark symbol from config.edn."
  ([ds]
   (refresh-beta! ds (benchmark-symbol)))
  ([ds benchmark-sym]
   (ensure-schema! ds)
   (jdbc/execute! ds [beta-sql benchmark-sym])))
