(ns options-trader.indicators.earnings-views
  "Earnings-derived indicator columns. Source SQL:
   resources/sql/indicators.sql (earnings-views)."
  (:require [options-trader.db.queries.indicators :as q]))

(def ^:private earnings-events-cols
  [[:implied_move_pct_pre_event "DOUBLE"]
   [:gross_margin               "DOUBLE"]
   [:operating_margin           "DOUBLE"]
   [:guidance_direction         "VARCHAR"]])

(def ^:private latest-indicator-cols
  [[:avg_earnings_move_pct             "DOUBLE"]
   [:earnings_move_count_above_implied "DOUBLE"]
   [:days_since_earnings               "DOUBLE"]
   [:days_to_next_earnings             "DOUBLE"]
   [:gap_held_flag                     "BOOLEAN"]
   [:earnings_day_volume_ratio         "DOUBLE"]
   [:gross_margin_delta_qoq            "DOUBLE"]
   [:operating_margin_delta_qoq        "DOUBLE"]
   [:eps_surprise_pct                  "DOUBLE"]
   [:revenue_surprise_pct              "DOUBLE"]
   [:guidance_direction                "VARCHAR"]])

(defn- ensure-schema! [ds]
  (doseq [[col t] earnings-events-cols]
    (q/ensure-column-with-type! ds :earnings_events col t))
  (doseq [[col t] latest-indicator-cols]
    (q/ensure-column-with-type! ds :latest_indicators col t)))

(defn refresh-earnings-views!
  "Compute earnings-derived columns from earnings_events / earnings_calendar
   / bars_daily and upsert into latest_indicators. NULLs for symbols with
   no earnings history."
  [ds]
  (ensure-schema! ds)
  (q/run-static-recompute! ds (q/earnings-views-sqlvec)))
