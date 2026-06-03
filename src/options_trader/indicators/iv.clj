(ns options-trader.indicators.iv
  "IV/HV derived indicators. Source SQL: resources/sql/indicators.sql
   (iv-window CTE). Reads iv_daily, writes the 10 iv_rank_* /
   iv_percentile_* / hv_* / iv_minus_hv / iv_rank_window_used columns
   into latest_indicators."
  (:require [options-trader.db.queries.indicators :as q]))

(def ^:private iv-double-cols
  [:iv_rank_252d :iv_rank_126d :iv_rank_63d
   :iv_percentile_252d :iv_percentile_126d :iv_percentile_63d
   :hv_rank_252d :hv_percentile_252d
   :iv_minus_hv])

(defn refresh-iv-indicators!
  "Compute IV/HV derived columns from iv_daily and upsert into
   latest_indicators. Adds the 10 columns if absent."
  [ds]
  (q/ensure-double-columns!  ds iv-double-cols)
  (q/ensure-varchar-column!  ds :iv_rank_window_used)
  (q/run-static-recompute!   ds (q/iv-window-sqlvec)))
