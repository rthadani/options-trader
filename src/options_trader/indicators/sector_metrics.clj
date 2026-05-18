(ns options-trader.indicators.sector-metrics
  "Per-sector PE/EV-EBITDA quartiles and within-sector percent_rank for each symbol.
   Reads sector_map and the latest fundamentals row per symbol, computes
   quantile_cont quartiles per sector into sector_metrics, then computes
   PERCENT_RANK within sector and upserts pe_vs_sector_pct and
   ev_ebitda_vs_sector_pct into latest_indicators."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.time LocalDate]))

;;; ── Schema ───────────────────────────────────────────────────────────────────

(defn- ensure-schema! [ds]
  ;; Bootstrap migration creates sector_metrics with a different PK (met_date, ret_1d, ...).
  ;; If our desired ts column is absent, drop and recreate with the PE/EV quartile schema.
  (let [ts-rows (jdbc/execute! ds
                  ["SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'sector_metrics' AND column_name = 'ts'"]
                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when (empty? ts-rows)
      (jdbc/execute! ds ["DROP TABLE IF EXISTS sector_metrics"])))
  (jdbc/execute! ds
    ["CREATE TABLE IF NOT EXISTS sector_metrics (
        sector        VARCHAR NOT NULL,
        ts            DATE    NOT NULL,
        pe_p25        DOUBLE,
        pe_p50        DOUBLE,
        pe_p75        DOUBLE,
        ev_ebitda_p25 DOUBLE,
        ev_ebitda_p50 DOUBLE,
        ev_ebitda_p75 DOUBLE,
        PRIMARY KEY (sector, ts)
      )"])
  (jdbc/execute! ds
    ["ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS pe_vs_sector_pct DOUBLE"])
  (jdbc/execute! ds
    ["ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS ev_ebitda_vs_sector_pct DOUBLE"]))

;;; ── SQL ──────────────────────────────────────────────────────────────────────

(def ^:private sector-quartile-sql
  "WITH
   latest_fund AS (
     SELECT symbol,
            CAST(data->>'pe_ratio'  AS DOUBLE) AS pe_ratio,
            CAST(data->>'ev_ebitda' AS DOUBLE) AS ev_ebitda
     FROM fundamentals
     QUALIFY ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY period DESC) = 1
   ),
   sector_symbols AS (
     SELECT lf.symbol, sm.sector, lf.pe_ratio, lf.ev_ebitda
     FROM latest_fund lf
     JOIN sector_map sm ON sm.symbol = lf.symbol
     WHERE sm.sector IS NOT NULL
   )
   SELECT
     sector,
     quantile_cont(pe_ratio,  0.25) AS pe_p25,
     quantile_cont(pe_ratio,  0.50) AS pe_p50,
     quantile_cont(pe_ratio,  0.75) AS pe_p75,
     quantile_cont(ev_ebitda, 0.25) AS ev_ebitda_p25,
     quantile_cont(ev_ebitda, 0.50) AS ev_ebitda_p50,
     quantile_cont(ev_ebitda, 0.75) AS ev_ebitda_p75
   FROM sector_symbols
   GROUP BY sector")

(def ^:private symbol-percentrank-sql
  "WITH
   latest_fund AS (
     SELECT symbol,
            CAST(data->>'pe_ratio'  AS DOUBLE) AS pe_ratio,
            CAST(data->>'ev_ebitda' AS DOUBLE) AS ev_ebitda
     FROM fundamentals
     QUALIFY ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY period DESC) = 1
   ),
   sector_symbols AS (
     SELECT lf.symbol, sm.sector, lf.pe_ratio, lf.ev_ebitda
     FROM latest_fund lf
     JOIN sector_map sm ON sm.symbol = lf.symbol
     WHERE sm.sector IS NOT NULL
   )
   SELECT
     symbol,
     PERCENT_RANK() OVER (PARTITION BY sector ORDER BY pe_ratio  ASC) AS pe_vs_sector_pct,
     PERCENT_RANK() OVER (PARTITION BY sector ORDER BY ev_ebitda ASC) AS ev_ebitda_vs_sector_pct
   FROM sector_symbols")

;;; ── Upsert helpers ───────────────────────────────────────────────────────────

(defn- upsert-sector-metrics-row! [ds sector ts values]
  (let [col-names  (mapv name (keys values))
        col-vals   (vec (vals values))
        set-clause (str/join ", " (map #(str % " = excluded." %) col-names))
        sql (str "INSERT INTO sector_metrics (sector, ts, "
                 (str/join ", " col-names)
                 ") VALUES (?, ?, "
                 (str/join ", " (repeat (count col-names) "?"))
                 ") ON CONFLICT (sector, ts) DO UPDATE SET "
                 set-clause)]
    (jdbc/execute! ds (into [sql sector ts] col-vals))))

(defn- upsert-indicator-row! [ds sym values]
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

;;; ── Public entry point ───────────────────────────────────────────────────────

(defn refresh-sector-metrics!
  "Compute per-sector pe/ev-ebitda quartiles via quantile_cont and per-symbol
   PERCENT_RANK within sector. Upserts one row per sector into sector_metrics
   (keyed on sector + today's date) and upserts pe_vs_sector_pct and
   ev_ebitda_vs_sector_pct into latest_indicators (one row per symbol).
   Symbols missing from sector_map or fundamentals receive NULL for both columns."
  [ds]
  (ensure-schema! ds)
  (let [today (LocalDate/now)
        q-rows (jdbc/execute! ds [sector-quartile-sql]
                              {:builder-fn rs/as-unqualified-lower-maps})]
    (doseq [row q-rows]
      (upsert-sector-metrics-row! ds (:sector row) today (dissoc row :sector))))
  (let [r-rows (jdbc/execute! ds [symbol-percentrank-sql]
                              {:builder-fn rs/as-unqualified-lower-maps})]
    (doseq [row r-rows]
      (upsert-indicator-row! ds (:symbol row) (dissoc row :symbol)))))
