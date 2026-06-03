(ns options-trader.indicators.sector-metrics
  "Per-sector PE / EV-EBITDA quartiles + per-symbol within-sector
   percent_rank. Source SQL: resources/sql/indicators.sql
   (sector-quartiles, symbol-sector-percentrank, plus the
   sector_metrics table DDL)."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.db.queries.indicators :as q])
  (:import [java.time LocalDate]))

(def ^:private as-lower {:builder-fn rs/as-unqualified-lower-maps})

(defn- ensure-schema! [ds]
  ;; Bootstrap migration creates sector_metrics with a different PK
  ;; (met_date, ret_1d, ...). If our :ts column is absent, drop and
  ;; recreate with the PE/EV quartile schema.
  (let [has-ts? (seq (jdbc/execute! ds (q/sector-metrics-has-ts-sqlvec) as-lower))]
    (when-not has-ts?
      (jdbc/execute! ds (q/drop-sector-metrics-sqlvec))))
  (jdbc/execute! ds (q/create-sector-metrics-table-sqlvec))
  (q/ensure-double-columns! ds [:pe_vs_sector_pct :ev_ebitda_vs_sector_pct]))

(def ^:private sector-metrics-table-opts
  {:table :sector_metrics :key-cols [:sector :ts]})

(defn refresh-sector-metrics!
  "Compute per-sector PE / EV-EBITDA quartiles and per-symbol within-
   sector PERCENT_RANK. Upserts quartiles into sector_metrics (one row
   per sector for today) and pe_vs_sector_pct + ev_ebitda_vs_sector_pct
   into latest_indicators. NULLs for symbols missing from sector_map or
   fundamentals."
  [ds]
  (ensure-schema! ds)
  (let [today    (LocalDate/now)
        q-rows   (jdbc/execute! ds (q/sector-quartiles-sqlvec) as-lower)
        r-rows   (jdbc/execute! ds (q/symbol-sector-percentrank-sqlvec) as-lower)]
    (doseq [row q-rows]
      (q/upsert-row! ds sector-metrics-table-opts
                     [(:sector row) today]
                     (dissoc row :sector)))
    (doseq [row r-rows]
      (q/upsert-latest-row! ds (:symbol row) (dissoc row :symbol)))))
