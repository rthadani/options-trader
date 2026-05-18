(ns options-trader.indicators.iv
  "IV/HV derived indicators: computes iv_rank, iv_percentile, hv_rank,
   hv_percentile, iv_minus_hv, and iv_rank_window_used from iv_daily via
   DuckDB window queries, then upserts one row per symbol into latest_indicators."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;;; ── Schema ───────────────────────────────────────────────────────────────────

(def ^:private iv-double-cols
  [:iv_rank_252d :iv_rank_126d :iv_rank_63d
   :iv_percentile_252d :iv_percentile_126d :iv_percentile_63d
   :hv_rank_252d :hv_percentile_252d
   :iv_minus_hv])

(defn- ensure-iv-columns! [ds]
  (doseq [col iv-double-cols]
    (jdbc/execute! ds
      [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS "
            (name col) " DOUBLE")]))
  (jdbc/execute! ds
    ["ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS iv_rank_window_used VARCHAR"]))

;;; ── SQL ──────────────────────────────────────────────────────────────────────

(def ^:private iv-window-sql
  "WITH
   rows_ranked AS (
     SELECT symbol, iv_date, iv30, hv30,
            ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY iv_date DESC) AS rn
     FROM iv_daily
   ),
   latest_row AS (
     SELECT symbol, iv30, hv30 FROM rows_ranked WHERE rn = 1
   ),
   w252 AS (
     SELECT r.symbol,
            COUNT(*) AS cnt,
            MIN(r.iv30) AS min_iv, MAX(r.iv30) AS max_iv,
            MIN(r.hv30) AS min_hv, MAX(r.hv30) AS max_hv,
            SUM(CASE WHEN r.iv30 < l.iv30 THEN 1.0 ELSE 0.0 END)
              / NULLIF(COUNT(*), 0) * 100.0 AS pct_iv,
            SUM(CASE WHEN r.hv30 < l.hv30 THEN 1.0 ELSE 0.0 END)
              / NULLIF(COUNT(*), 0) * 100.0 AS pct_hv
     FROM latest_row l
     JOIN rows_ranked r ON r.symbol = l.symbol AND r.rn <= 252
     GROUP BY r.symbol, l.iv30, l.hv30
   ),
   w126 AS (
     SELECT r.symbol,
            COUNT(*) AS cnt,
            MIN(r.iv30) AS min_iv, MAX(r.iv30) AS max_iv,
            SUM(CASE WHEN r.iv30 < l.iv30 THEN 1.0 ELSE 0.0 END)
              / NULLIF(COUNT(*), 0) * 100.0 AS pct_iv
     FROM latest_row l
     JOIN rows_ranked r ON r.symbol = l.symbol AND r.rn <= 126
     GROUP BY r.symbol, l.iv30
   ),
   w63 AS (
     SELECT r.symbol,
            COUNT(*) AS cnt,
            MIN(r.iv30) AS min_iv, MAX(r.iv30) AS max_iv,
            SUM(CASE WHEN r.iv30 < l.iv30 THEN 1.0 ELSE 0.0 END)
              / NULLIF(COUNT(*), 0) * 100.0 AS pct_iv
     FROM latest_row l
     JOIN rows_ranked r ON r.symbol = l.symbol AND r.rn <= 63
     GROUP BY r.symbol, l.iv30
   )
   SELECT
     l.symbol,
     CASE WHEN w252.cnt >= 252 AND l.iv30 IS NOT NULL
          THEN (l.iv30 - w252.min_iv) / NULLIF(w252.max_iv - w252.min_iv, 0.0) * 100.0
     END AS iv_rank_252d,
     CASE WHEN w126.cnt >= 126 AND l.iv30 IS NOT NULL
          THEN (l.iv30 - w126.min_iv) / NULLIF(w126.max_iv - w126.min_iv, 0.0) * 100.0
     END AS iv_rank_126d,
     CASE WHEN w63.cnt  >= 63  AND l.iv30 IS NOT NULL
          THEN (l.iv30 - w63.min_iv)  / NULLIF(w63.max_iv  - w63.min_iv,  0.0) * 100.0
     END AS iv_rank_63d,
     CASE WHEN w252.cnt >= 252 AND l.iv30 IS NOT NULL THEN w252.pct_iv END AS iv_percentile_252d,
     CASE WHEN w126.cnt >= 126 AND l.iv30 IS NOT NULL THEN w126.pct_iv END AS iv_percentile_126d,
     CASE WHEN w63.cnt  >= 63  AND l.iv30 IS NOT NULL THEN w63.pct_iv  END AS iv_percentile_63d,
     CASE WHEN w252.cnt >= 252 AND l.hv30 IS NOT NULL
          THEN (l.hv30 - w252.min_hv) / NULLIF(w252.max_hv - w252.min_hv, 0.0) * 100.0
     END AS hv_rank_252d,
     CASE WHEN w252.cnt >= 252 AND l.hv30 IS NOT NULL THEN w252.pct_hv END AS hv_percentile_252d,
     CASE WHEN l.iv30 IS NOT NULL AND l.hv30 IS NOT NULL THEN l.iv30 - l.hv30 END AS iv_minus_hv,
     CASE
       WHEN w252.cnt >= 252 AND l.iv30 IS NOT NULL THEN '252d'
       WHEN w126.cnt >= 126 AND l.iv30 IS NOT NULL THEN '126d'
       WHEN w63.cnt  >= 63  AND l.iv30 IS NOT NULL THEN '63d'
       ELSE NULL
     END AS iv_rank_window_used
   FROM latest_row l
   LEFT JOIN w252 ON l.symbol = w252.symbol
   LEFT JOIN w126 ON l.symbol = w126.symbol
   LEFT JOIN w63  ON l.symbol = w63.symbol")

;;; ── Upsert helper ────────────────────────────────────────────────────────────

(defn- upsert-iv-row! [ds symbol values]
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

;;; ── Public entry point ───────────────────────────────────────────────────────

(defn refresh-iv-indicators!
  "Compute IV/HV derived columns from iv_daily and upsert into latest_indicators.
   Adds the 10 new columns if absent. Processes all symbols present in iv_daily."
  [ds]
  (ensure-iv-columns! ds)
  (let [rows (jdbc/execute! ds [iv-window-sql]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (doseq [row rows]
      (let [sym    (:symbol row)
            values (dissoc row :symbol)]
        (upsert-iv-row! ds sym values)))))
