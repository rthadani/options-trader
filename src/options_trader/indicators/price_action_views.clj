(ns options-trader.indicators.price-action-views
  "Price-action and volume indicators (close vs SMA-50/200, distance %,
   5d/20d volume ratio). Source SQL: resources/sql/indicators.sql
   (price-action-views)."
  (:require [options-trader.db.queries.indicators :as q]))

(def ^:private cols
  [:close_vs_sma_50 :close_vs_sma_200
   :distance_to_sma_50_pct :distance_to_sma_200_pct
   :volume_ratio_5d_vs_20d])

(defn ensure-schema! [ds] (q/ensure-double-columns! ds cols))

(defn refresh-price-action-views!
  "Compute price-action / volume columns from bars_daily and upsert into
   latest_indicators. Columns are NULL when fewer bars than the required
   window exist (50, 200, or 20)."
  [ds]
  (ensure-schema! ds)
  (q/run-static-recompute! ds (q/price-action-views-sqlvec)))
