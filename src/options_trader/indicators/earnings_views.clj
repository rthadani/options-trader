(ns options-trader.indicators.earnings-views
  "Earnings-derived indicators surfaced as columns on latest_indicators.
   Pure SQL over earnings_events, earnings_calendar, and bars_daily.
   Computes avg_earnings_move_pct (last-8 window), earnings_move_count_above_implied,
   days_since/to_earnings, gap_held_flag, earnings_day_volume_ratio,
   gross/operating_margin_delta_qoq, and eps/revenue_surprise_pct passthrough."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;;; ── Schema ────────────────────────────────────────────────────────────────────

(defn- ensure-schema! [ds]
  ;; Extend earnings_events with columns populated by the data ingestion layer
  (doseq [stmt
          ["ALTER TABLE earnings_events ADD COLUMN IF NOT EXISTS implied_move_pct_pre_event DOUBLE"
           "ALTER TABLE earnings_events ADD COLUMN IF NOT EXISTS gross_margin DOUBLE"
           "ALTER TABLE earnings_events ADD COLUMN IF NOT EXISTS operating_margin DOUBLE"
           "ALTER TABLE earnings_events ADD COLUMN IF NOT EXISTS guidance_direction VARCHAR"]]
    (jdbc/execute! ds [stmt]))
  ;; Add derived columns to latest_indicators
  (doseq [[col sql-type]
          [["avg_earnings_move_pct"            "DOUBLE"]
           ["earnings_move_count_above_implied" "DOUBLE"]
           ["days_since_earnings"               "DOUBLE"]
           ["days_to_next_earnings"             "DOUBLE"]
           ["gap_held_flag"                     "BOOLEAN"]
           ["earnings_day_volume_ratio"         "DOUBLE"]
           ["gross_margin_delta_qoq"            "DOUBLE"]
           ["operating_margin_delta_qoq"        "DOUBLE"]
           ["eps_surprise_pct"                  "DOUBLE"]
           ["revenue_surprise_pct"              "DOUBLE"]
           ["guidance_direction"                "VARCHAR"]]]
    (jdbc/execute! ds
      [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS " col " " sql-type)])))

;;; ── SQL ──────────────────────────────────────────────────────────────────────

(def ^:private earnings-views-sql
  "WITH
   all_symbols AS (
     SELECT DISTINCT symbol FROM earnings_events
     UNION
     SELECT DISTINCT symbol FROM earnings_calendar
   ),
   ranked_events AS (
     SELECT
       symbol, period, reported_at,
       eps_actual, eps_estimate,
       rev_actual, rev_estimate,
       implied_move_pct_pre_event,
       gross_margin, operating_margin,
       guidance_direction,
       ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY reported_at DESC) AS rn
     FROM earnings_events
     WHERE reported_at IS NOT NULL
   ),
   last8 AS (
     SELECT * FROM ranked_events WHERE rn <= 8
   ),
   event_bars AS (
     SELECT
       l8.symbol, l8.rn,
       l8.eps_actual, l8.eps_estimate,
       l8.rev_actual, l8.rev_estimate,
       l8.implied_move_pct_pre_event,
       l8.gross_margin, l8.operating_margin,
       l8.guidance_direction,
       (SELECT b.close FROM bars_daily b
        WHERE b.symbol = l8.symbol AND b.bar_date < l8.reported_at
        ORDER BY b.bar_date DESC LIMIT 1)                            AS close_before,
       (SELECT b.close FROM bars_daily b
        WHERE b.symbol = l8.symbol AND b.bar_date > l8.reported_at
        ORDER BY b.bar_date ASC  LIMIT 1)                            AS close_after,
       (SELECT b.open FROM bars_daily b
        WHERE b.symbol = l8.symbol AND b.bar_date > l8.reported_at
        ORDER BY b.bar_date ASC  LIMIT 1)                            AS open_after,
       (SELECT b.volume FROM bars_daily b
        WHERE b.symbol = l8.symbol AND b.bar_date = l8.reported_at
        LIMIT 1)                                                      AS volume_event,
       (SELECT AVG(sub.volume)
        FROM (SELECT volume FROM bars_daily b2
              WHERE b2.symbol = l8.symbol AND b2.bar_date < l8.reported_at
              ORDER BY b2.bar_date DESC LIMIT 20) AS sub)            AS avg_vol_20d
     FROM last8 l8
   ),
   per_event AS (
     SELECT
       symbol, rn,
       guidance_direction, gross_margin, operating_margin,
       implied_move_pct_pre_event,
       CASE WHEN close_before IS NOT NULL AND close_after IS NOT NULL
                 AND close_before > 0
            THEN ABS(close_after - close_before) / close_before END  AS realized_move_pct,
       CASE WHEN close_after IS NOT NULL AND open_after IS NOT NULL
            THEN (close_after > open_after) END                       AS gap_held,
       CASE WHEN volume_event IS NOT NULL AND avg_vol_20d IS NOT NULL
                 AND avg_vol_20d > 0
            THEN CAST(volume_event AS DOUBLE) / avg_vol_20d END       AS earnings_day_volume_ratio,
       CASE WHEN eps_estimate IS NOT NULL AND ABS(eps_estimate) > 0
                 AND eps_actual IS NOT NULL
            THEN (eps_actual - eps_estimate) / ABS(eps_estimate) * 100.0 END AS eps_surprise_pct,
       CASE WHEN rev_estimate IS NOT NULL AND ABS(rev_estimate) > 0
                 AND rev_actual IS NOT NULL
            THEN (rev_actual - rev_estimate) / ABS(rev_estimate) * 100.0 END AS revenue_surprise_pct
     FROM event_bars
   ),
   aggregated AS (
     SELECT
       symbol,
       AVG(realized_move_pct)                                         AS avg_earnings_move_pct,
       CAST(COUNT(CASE WHEN realized_move_pct IS NOT NULL
                            AND implied_move_pct_pre_event IS NOT NULL
                            AND realized_move_pct > implied_move_pct_pre_event
                       THEN 1 END) AS DOUBLE)                         AS earnings_move_count_above_implied
     FROM per_event
     GROUP BY symbol
   ),
   most_recent AS (SELECT * FROM per_event WHERE rn = 1),
   prev_event  AS (SELECT * FROM per_event WHERE rn = 2),
   next_cal AS (
     SELECT symbol, MIN(report_date) AS next_date
     FROM earnings_calendar
     WHERE report_date > CURRENT_DATE
     GROUP BY symbol
   ),
   latest_reported AS (
     SELECT symbol, MAX(reported_at) AS last_date
     FROM earnings_events
     WHERE reported_at IS NOT NULL
     GROUP BY symbol
   )
   SELECT
     s.symbol,
     agg.avg_earnings_move_pct,
     agg.earnings_move_count_above_implied,
     CAST(DATEDIFF('day', lr.last_date, CURRENT_DATE) AS DOUBLE)      AS days_since_earnings,
     CAST(DATEDIFF('day', CURRENT_DATE, nc.next_date)  AS DOUBLE)     AS days_to_next_earnings,
     mr.gap_held                                                        AS gap_held_flag,
     mr.earnings_day_volume_ratio,
     CASE WHEN mr.gross_margin IS NOT NULL AND pv.gross_margin IS NOT NULL
          THEN mr.gross_margin - pv.gross_margin END                   AS gross_margin_delta_qoq,
     CASE WHEN mr.operating_margin IS NOT NULL AND pv.operating_margin IS NOT NULL
          THEN mr.operating_margin - pv.operating_margin END           AS operating_margin_delta_qoq,
     mr.eps_surprise_pct,
     mr.revenue_surprise_pct,
     mr.guidance_direction
   FROM all_symbols s
   LEFT JOIN aggregated     agg ON agg.symbol = s.symbol
   LEFT JOIN most_recent     mr ON  mr.symbol = s.symbol
   LEFT JOIN prev_event      pv ON  pv.symbol = s.symbol
   LEFT JOIN next_cal        nc ON  nc.symbol = s.symbol
   LEFT JOIN latest_reported lr ON  lr.symbol = s.symbol")

;;; ── Upsert ────────────────────────────────────────────────────────────────────

(defn- upsert-earnings-row! [ds sym values]
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

(defn refresh-earnings-views!
  "Compute earnings-derived indicator columns from earnings_events, earnings_calendar,
   and bars_daily, then upsert one row per symbol into latest_indicators.
   Symbols absent from both earnings tables receive NULL for all derived columns."
  [ds]
  (ensure-schema! ds)
  (let [rows (jdbc/execute! ds [earnings-views-sql]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (doseq [row rows]
      (upsert-earnings-row! ds (:symbol row) (dissoc row :symbol)))))
