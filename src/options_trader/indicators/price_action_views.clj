(ns options-trader.indicators.price-action-views
  "Price-action and volume indicators surfaced as columns on latest_indicators.
   Pure DuckDB window SQL over bars_daily.
   Computes close_vs_sma_50, close_vs_sma_200, distance_to_sma_50_pct,
   distance_to_sma_200_pct, and volume_ratio_5d_vs_20d.
   Columns are NULL when fewer bars exist than the required window size (50/200/20).
   NULLIF on each divisor guards against divide-by-zero when SMA equals zero."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;;; ── Schema ────────────────────────────────────────────────────────────────────

(defn ensure-schema!
  "Add the five price-action/volume DOUBLE columns to latest_indicators.
   ALTER TABLE ... ADD COLUMN IF NOT EXISTS is idempotent."
  [ds]
  (doseq [[col sql-type]
          [["close_vs_sma_50"         "DOUBLE"]
           ["close_vs_sma_200"        "DOUBLE"]
           ["distance_to_sma_50_pct"  "DOUBLE"]
           ["distance_to_sma_200_pct" "DOUBLE"]
           ["volume_ratio_5d_vs_20d"  "DOUBLE"]]]
    (jdbc/execute! ds
      [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS " col " " sql-type)])))

;;; ── SQL ──────────────────────────────────────────────────────────────────────

(def ^:private price-action-sql
  "WITH
   bars_with_windows AS (
     SELECT
       symbol,
       bar_date                                                                                               AS ts,
       close,
       AVG(close)  OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 49  PRECEDING AND CURRENT ROW)  AS sma_50_raw,
       AVG(close)  OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 199 PRECEDING AND CURRENT ROW)  AS sma_200_raw,
       AVG(volume) OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 4   PRECEDING AND CURRENT ROW)  AS avg_vol_5d,
       AVG(volume) OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 19  PRECEDING AND CURRENT ROW)  AS avg_vol_20d,
       COUNT(*)    OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 49  PRECEDING AND CURRENT ROW)  AS cnt_50,
       COUNT(*)    OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 199 PRECEDING AND CURRENT ROW)  AS cnt_200,
       COUNT(*)    OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 19  PRECEDING AND CURRENT ROW)  AS cnt_20
     FROM bars_daily
   ),
   latest_per_symbol AS (
     SELECT *
     FROM bars_with_windows
     QUALIFY ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY ts DESC) = 1
   )
   SELECT
     symbol,
     close / NULLIF(CASE WHEN cnt_50  >= 50  THEN sma_50_raw  ELSE NULL END, 0.0)    AS close_vs_sma_50,
     close / NULLIF(CASE WHEN cnt_200 >= 200 THEN sma_200_raw ELSE NULL END, 0.0)    AS close_vs_sma_200,
     (close - CASE WHEN cnt_50  >= 50  THEN sma_50_raw  ELSE NULL END)
       / NULLIF(CASE WHEN cnt_50  >= 50  THEN sma_50_raw  ELSE NULL END, 0.0)        AS distance_to_sma_50_pct,
     (close - CASE WHEN cnt_200 >= 200 THEN sma_200_raw ELSE NULL END)
       / NULLIF(CASE WHEN cnt_200 >= 200 THEN sma_200_raw ELSE NULL END, 0.0)        AS distance_to_sma_200_pct,
     avg_vol_5d / NULLIF(CASE WHEN cnt_20 >= 20 THEN avg_vol_20d ELSE NULL END, 0.0) AS volume_ratio_5d_vs_20d
   FROM latest_per_symbol")

;;; ── Upsert ────────────────────────────────────────────────────────────────────

(defn- upsert-row! [ds sym values]
  (let [col-names  (mapv name (keys values))
        col-vals   (vec (vals values))
        set-clause (str/join ", " (map #(str % " = excluded." %) col-names))
        sql (str "INSERT INTO latest_indicators (symbol, "
                 (str/join ", " col-names)
                 ") VALUES (?, "
                 (str/join ", " (repeat (count col-names) "?"))
                 ") ON CONFLICT (symbol) DO UPDATE SET "
                 set-clause)]
    (jdbc/execute! ds (into [sql sym] col-vals))))

;;; ── Public entry point ────────────────────────────────────────────────────────

(defn refresh-price-action-views!
  "Compute price-action and volume indicator columns from bars_daily,
   then upsert one row per symbol into latest_indicators.
   Three-valued logic: columns are NULL when fewer bars exist than the
   required window size (50 for sma_50 columns, 200 for sma_200 columns,
   20 for volume_ratio_5d_vs_20d). NULLIF on each SMA divisor prevents
   divide-by-zero when all closes in the window are zero."
  [ds]
  (ensure-schema! ds)
  (let [rows (jdbc/execute! ds [price-action-sql]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (doseq [row rows]
      (upsert-row! ds (:symbol row) (dissoc row :symbol)))))
