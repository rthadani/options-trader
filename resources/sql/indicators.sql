-- Indicator queries: ad-hoc lookups + the big static recompute CTEs run
-- by refresh-derived-indicators!. Per-row dynamic upserts (which columns
-- to write depends on which had computed values) are NOT here — those are
-- driven by db.queries.indicators/upsert-latest-row!.

-- :name select-latest-indicators :? :*
SELECT * FROM latest_indicators WHERE symbol IN (:v*:symbols);

-- :name all-bars-daily-symbols :? :*
SELECT DISTINCT symbol FROM bars_daily ORDER BY symbol;

-- :name load-bars-daily :? :*
SELECT bar_date, open, high, low, close, volume
  FROM bars_daily
 WHERE symbol = :symbol
 ORDER BY bar_date ASC;

-- :name insert-indicator-history :! :n
INSERT INTO indicator_history (symbol, indicator, ind_date, value)
VALUES (:symbol, :indicator, :ind-date, :value)
ON CONFLICT DO NOTHING;

-- :name percent-rank-latest :? :*
-- PERCENT_RANK of the latest value for indicator over a trailing window
-- of :n-days days. (INTERVAL '1 day') * n is DuckDB's parameterised
-- INTERVAL form.
WITH w AS (
  SELECT value, ind_date,
         PERCENT_RANK() OVER (ORDER BY value) AS pr
  FROM indicator_history
  WHERE symbol = :symbol AND indicator = :indicator
    AND ind_date >= (
      SELECT MAX(ind_date) - (INTERVAL '1 day') * :n-days
      FROM indicator_history WHERE symbol = :symbol AND indicator = :indicator
    )
) SELECT pr FROM w ORDER BY ind_date DESC LIMIT 1;

-- :name latest-close :? :1
SELECT close FROM bars_daily WHERE symbol = :symbol
 ORDER BY bar_date DESC LIMIT 1;

-- :name persist-fundamentals :! :n
INSERT INTO fundamentals (symbol, period, fetched_at, data)
VALUES (:symbol, :period, current_timestamp, :data)
ON CONFLICT (symbol, period) DO UPDATE SET
  fetched_at = excluded.fetched_at,
  data       = excluded.data;

-- :name create-sector-metrics-table :! :n
CREATE TABLE IF NOT EXISTS sector_metrics (
  sector        VARCHAR NOT NULL,
  ts            DATE    NOT NULL,
  pe_p25        DOUBLE,
  pe_p50        DOUBLE,
  pe_p75        DOUBLE,
  ev_ebitda_p25 DOUBLE,
  ev_ebitda_p50 DOUBLE,
  ev_ebitda_p75 DOUBLE,
  PRIMARY KEY (sector, ts)
);

-- :name sector-metrics-has-ts :? :*
SELECT column_name FROM information_schema.columns
 WHERE table_name = 'sector_metrics' AND column_name = 'ts';

-- :name drop-sector-metrics :! :n
DROP TABLE IF EXISTS sector_metrics;

-- :name sector-quartiles :? :*
WITH
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
SELECT sector,
       quantile_cont(pe_ratio,  0.25) AS pe_p25,
       quantile_cont(pe_ratio,  0.50) AS pe_p50,
       quantile_cont(pe_ratio,  0.75) AS pe_p75,
       quantile_cont(ev_ebitda, 0.25) AS ev_ebitda_p25,
       quantile_cont(ev_ebitda, 0.50) AS ev_ebitda_p50,
       quantile_cont(ev_ebitda, 0.75) AS ev_ebitda_p75
FROM sector_symbols
GROUP BY sector;

-- :name symbol-sector-percentrank :? :*
WITH
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
SELECT symbol,
       PERCENT_RANK() OVER (PARTITION BY sector ORDER BY pe_ratio  ASC) AS pe_vs_sector_pct,
       PERCENT_RANK() OVER (PARTITION BY sector ORDER BY ev_ebitda ASC) AS ev_ebitda_vs_sector_pct
FROM sector_symbols;

-- :name option-chain-agg :? :*
-- Aggregate option_chain across the ATM ±1 strike window of the front
-- two expirations. Identical to option-chain-agg-for except this one
-- processes every symbol with a bars_daily row.
WITH
latest_close AS (
  SELECT symbol, close FROM bars_daily
  QUALIFY ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY bar_date DESC) = 1
),
latest_chain AS (
  SELECT symbol, expiry, strike, opt_right, bid, ask, volume, open_interest
  FROM option_chain
  QUALIFY ROW_NUMBER() OVER (
    PARTITION BY symbol, expiry, strike, opt_right ORDER BY fetched_at DESC
  ) = 1
),
front2_exp AS (
  SELECT symbol, expiry, dr AS exp_rank FROM (
    SELECT DISTINCT lc.symbol, lc.expiry,
           DENSE_RANK() OVER (PARTITION BY lc.symbol ORDER BY lc.expiry) AS dr
    FROM latest_chain lc
    JOIN latest_close cl ON cl.symbol = lc.symbol
    WHERE lc.expiry >= CURRENT_DATE
  ) WHERE dr <= 2
),
strike_ranks AS (
  SELECT symbol, expiry, strike,
         ROW_NUMBER() OVER (PARTITION BY symbol, expiry ORDER BY strike) AS s_rank
  FROM (
    SELECT DISTINCT lc.symbol, lc.expiry, lc.strike
    FROM latest_chain lc
    JOIN front2_exp fe ON fe.symbol = lc.symbol AND fe.expiry = lc.expiry
  )
),
atm_strike_per_exp AS (
  SELECT lc.symbol, lc.expiry,
         arg_min(lc.strike, ABS(lc.strike - cl.close)) AS atm_strike
  FROM latest_chain lc
  JOIN front2_exp fe ON fe.symbol = lc.symbol AND fe.expiry = lc.expiry
  JOIN latest_close cl ON cl.symbol = lc.symbol
  GROUP BY lc.symbol, lc.expiry
),
atm_ranks AS (
  SELECT sr.symbol, sr.expiry, sr.s_rank AS atm_rank
  FROM strike_ranks sr
  JOIN atm_strike_per_exp atm ON atm.symbol = sr.symbol
                             AND atm.expiry = sr.expiry
                             AND atm.atm_strike = sr.strike
),
window_data AS (
  SELECT lc.symbol, lc.expiry, lc.strike, lc.opt_right,
         lc.bid, lc.ask, lc.volume, lc.open_interest,
         fe.exp_rank, atm.atm_strike
  FROM latest_chain lc
  JOIN front2_exp fe ON fe.symbol = lc.symbol AND fe.expiry = lc.expiry
  JOIN strike_ranks sr ON sr.symbol = lc.symbol AND sr.expiry = lc.expiry
                      AND sr.strike = lc.strike
  JOIN atm_ranks ar ON ar.symbol = lc.symbol AND ar.expiry = lc.expiry
  JOIN atm_strike_per_exp atm ON atm.symbol = lc.symbol AND atm.expiry = lc.expiry
  WHERE sr.s_rank BETWEEN ar.atm_rank - 1 AND ar.atm_rank + 1
),
aggregated AS (
  SELECT symbol,
         CAST(SUM(open_interest) AS DOUBLE) AS option_oi_total,
         CAST(SUM(volume)         AS DOUBLE) AS option_vol_total,
         MAX(CASE WHEN exp_rank = 1 AND opt_right = 'C' AND strike = atm_strike
                  THEN (bid + ask) / 2.0 END) AS call_atm_mid,
         MAX(CASE WHEN exp_rank = 1 AND opt_right = 'P' AND strike = atm_strike
                  THEN (bid + ask) / 2.0 END) AS put_atm_mid,
         CAST(SUM(CASE WHEN opt_right = 'P' THEN open_interest END) AS DOUBLE)
           / NULLIF(SUM(CASE WHEN opt_right = 'C' THEN open_interest END), 0)
           AS put_call_oi_ratio,
         CAST(SUM(CASE WHEN opt_right = 'P' THEN volume END) AS DOUBLE)
           / NULLIF(SUM(CASE WHEN opt_right = 'C' THEN volume END), 0)
           AS put_call_volume_ratio,
         AVG(CASE WHEN bid > 0 AND ask > 0
                  THEN (ask - bid) / ((ask + bid) / 2.0) END) AS bid_ask_spread_pct
  FROM window_data GROUP BY symbol
)
SELECT cl.symbol,
       agg.option_oi_total, agg.option_vol_total,
       CASE WHEN agg.call_atm_mid IS NOT NULL AND agg.put_atm_mid IS NOT NULL
            THEN agg.call_atm_mid + agg.put_atm_mid END AS atm_straddle_price,
       CASE WHEN agg.call_atm_mid IS NOT NULL AND agg.put_atm_mid IS NOT NULL
                 AND cl.close > 0
            THEN (agg.call_atm_mid + agg.put_atm_mid) / cl.close END AS implied_move_pct,
       agg.put_call_oi_ratio, agg.put_call_volume_ratio, agg.bid_ask_spread_pct
FROM latest_close cl
LEFT JOIN aggregated agg ON agg.symbol = cl.symbol;

-- :name option-chain-agg-for :? :*
-- Same as option-chain-agg but restricted to a caller-supplied symbol list.
WITH
latest_close AS (
  SELECT symbol, close FROM bars_daily
  WHERE symbol IN (:v*:symbols)
  QUALIFY ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY bar_date DESC) = 1
),
latest_chain AS (
  SELECT symbol, expiry, strike, opt_right, bid, ask, volume, open_interest
  FROM option_chain
  QUALIFY ROW_NUMBER() OVER (
    PARTITION BY symbol, expiry, strike, opt_right ORDER BY fetched_at DESC
  ) = 1
),
front2_exp AS (
  SELECT symbol, expiry, dr AS exp_rank FROM (
    SELECT DISTINCT lc.symbol, lc.expiry,
           DENSE_RANK() OVER (PARTITION BY lc.symbol ORDER BY lc.expiry) AS dr
    FROM latest_chain lc
    JOIN latest_close cl ON cl.symbol = lc.symbol
    WHERE lc.expiry >= CURRENT_DATE
  ) WHERE dr <= 2
),
strike_ranks AS (
  SELECT symbol, expiry, strike,
         ROW_NUMBER() OVER (PARTITION BY symbol, expiry ORDER BY strike) AS s_rank
  FROM (
    SELECT DISTINCT lc.symbol, lc.expiry, lc.strike
    FROM latest_chain lc
    JOIN front2_exp fe ON fe.symbol = lc.symbol AND fe.expiry = lc.expiry
  )
),
atm_strike_per_exp AS (
  SELECT lc.symbol, lc.expiry,
         arg_min(lc.strike, ABS(lc.strike - cl.close)) AS atm_strike
  FROM latest_chain lc
  JOIN front2_exp fe ON fe.symbol = lc.symbol AND fe.expiry = lc.expiry
  JOIN latest_close cl ON cl.symbol = lc.symbol
  GROUP BY lc.symbol, lc.expiry
),
atm_ranks AS (
  SELECT sr.symbol, sr.expiry, sr.s_rank AS atm_rank
  FROM strike_ranks sr
  JOIN atm_strike_per_exp atm ON atm.symbol = sr.symbol
                             AND atm.expiry = sr.expiry
                             AND atm.atm_strike = sr.strike
),
window_data AS (
  SELECT lc.symbol, lc.expiry, lc.strike, lc.opt_right,
         lc.bid, lc.ask, lc.volume, lc.open_interest,
         fe.exp_rank, atm.atm_strike
  FROM latest_chain lc
  JOIN front2_exp fe ON fe.symbol = lc.symbol AND fe.expiry = lc.expiry
  JOIN strike_ranks sr ON sr.symbol = lc.symbol AND sr.expiry = lc.expiry
                      AND sr.strike = lc.strike
  JOIN atm_ranks ar ON ar.symbol = lc.symbol AND ar.expiry = lc.expiry
  JOIN atm_strike_per_exp atm ON atm.symbol = lc.symbol AND atm.expiry = lc.expiry
  WHERE sr.s_rank BETWEEN ar.atm_rank - 1 AND ar.atm_rank + 1
),
aggregated AS (
  SELECT symbol,
         CAST(SUM(open_interest) AS DOUBLE) AS option_oi_total,
         CAST(SUM(volume)         AS DOUBLE) AS option_vol_total,
         MAX(CASE WHEN exp_rank = 1 AND opt_right = 'C' AND strike = atm_strike
                  THEN (bid + ask) / 2.0 END) AS call_atm_mid,
         MAX(CASE WHEN exp_rank = 1 AND opt_right = 'P' AND strike = atm_strike
                  THEN (bid + ask) / 2.0 END) AS put_atm_mid,
         CAST(SUM(CASE WHEN opt_right = 'P' THEN open_interest END) AS DOUBLE)
           / NULLIF(SUM(CASE WHEN opt_right = 'C' THEN open_interest END), 0)
           AS put_call_oi_ratio,
         CAST(SUM(CASE WHEN opt_right = 'P' THEN volume END) AS DOUBLE)
           / NULLIF(SUM(CASE WHEN opt_right = 'C' THEN volume END), 0)
           AS put_call_volume_ratio,
         AVG(CASE WHEN bid > 0 AND ask > 0
                  THEN (ask - bid) / ((ask + bid) / 2.0) END) AS bid_ask_spread_pct
  FROM window_data GROUP BY symbol
)
SELECT cl.symbol,
       agg.option_oi_total, agg.option_vol_total,
       CASE WHEN agg.call_atm_mid IS NOT NULL AND agg.put_atm_mid IS NOT NULL
            THEN agg.call_atm_mid + agg.put_atm_mid END AS atm_straddle_price,
       CASE WHEN agg.call_atm_mid IS NOT NULL AND agg.put_atm_mid IS NOT NULL
                 AND cl.close > 0
            THEN (agg.call_atm_mid + agg.put_atm_mid) / cl.close END AS implied_move_pct,
       agg.put_call_oi_ratio, agg.put_call_volume_ratio, agg.bid_ask_spread_pct
FROM latest_close cl
LEFT JOIN aggregated agg ON agg.symbol = cl.symbol;

-- :name event-flags :? :*
-- :ma-patterns is a vector of single-element tuples, each carrying one
-- LIKE pattern: [["%merger%"] ["%acq%"] ...]. :tuple* spreads them as
-- (?), (?), ... so DuckDB sees a proper VALUES table.
WITH
syms AS (SELECT DISTINCT symbol FROM latest_indicators),
ma_hits AS (
  SELECT DISTINCT n.symbol FROM news n
   WHERE n.published_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
     AND TRY_CAST(n.data->>'sentiment_score' AS DOUBLE) > 0.2
     AND EXISTS (
       SELECT 1 FROM (VALUES :tuple*:ma-patterns) AS p(pat)
        WHERE lower(n.data->>'body') LIKE p.pat
     )
),
activist_hits AS (
  SELECT DISTINCT symbol FROM filings
   WHERE form_type IN ('13D', '13G')
     AND filed_at >= CURRENT_DATE - INTERVAL '90 days'
),
fda_hits AS (
  SELECT DISTINCT symbol FROM event_calendars
   WHERE event_type = 'fda'
     AND event_date BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL '14 days'
)
SELECT
  s.symbol,
  COALESCE(ma.symbol  IS NOT NULL, FALSE) AS ma_rumor_flag,
  COALESCE(ac.symbol  IS NOT NULL, FALSE) AS activist_filing_flag,
  COALESCE(fda.symbol IS NOT NULL, FALSE) AS fda_event_flag
FROM syms s
LEFT JOIN ma_hits       ma  ON s.symbol = ma.symbol
LEFT JOIN activist_hits ac  ON s.symbol = ac.symbol
LEFT JOIN fda_hits      fda ON s.symbol = fda.symbol;

-- :name price-action-views :? :*
WITH
bars_with_windows AS (
  SELECT
    symbol, bar_date AS ts, close,
    AVG(close)  OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 49  PRECEDING AND CURRENT ROW) AS sma_50_raw,
    AVG(close)  OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 199 PRECEDING AND CURRENT ROW) AS sma_200_raw,
    AVG(volume) OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 4   PRECEDING AND CURRENT ROW) AS avg_vol_5d,
    AVG(volume) OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 19  PRECEDING AND CURRENT ROW) AS avg_vol_20d,
    COUNT(*)    OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 49  PRECEDING AND CURRENT ROW) AS cnt_50,
    COUNT(*)    OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 199 PRECEDING AND CURRENT ROW) AS cnt_200,
    COUNT(*)    OVER (PARTITION BY symbol ORDER BY bar_date ROWS BETWEEN 19  PRECEDING AND CURRENT ROW) AS cnt_20
  FROM bars_daily
),
latest_per_symbol AS (
  SELECT * FROM bars_with_windows
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
FROM latest_per_symbol;

-- :name earnings-views :? :*
WITH
all_symbols AS (
  SELECT DISTINCT symbol FROM earnings_events
  UNION
  SELECT DISTINCT symbol FROM earnings_calendar
),
ranked_events AS (
  SELECT symbol, period, reported_at,
         eps_actual, eps_estimate, rev_actual, rev_estimate,
         implied_move_pct_pre_event,
         gross_margin, operating_margin, guidance_direction,
         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY reported_at DESC) AS rn
  FROM earnings_events WHERE reported_at IS NOT NULL
),
last8 AS (SELECT * FROM ranked_events WHERE rn <= 8),
event_bars AS (
  SELECT l8.symbol, l8.rn,
         l8.eps_actual, l8.eps_estimate, l8.rev_actual, l8.rev_estimate,
         l8.implied_move_pct_pre_event,
         l8.gross_margin, l8.operating_margin, l8.guidance_direction,
         (SELECT b.close FROM bars_daily b
           WHERE b.symbol = l8.symbol AND b.bar_date < l8.reported_at
           ORDER BY b.bar_date DESC LIMIT 1)  AS close_before,
         (SELECT b.close FROM bars_daily b
           WHERE b.symbol = l8.symbol AND b.bar_date > l8.reported_at
           ORDER BY b.bar_date ASC  LIMIT 1)  AS close_after,
         (SELECT b.open  FROM bars_daily b
           WHERE b.symbol = l8.symbol AND b.bar_date > l8.reported_at
           ORDER BY b.bar_date ASC  LIMIT 1)  AS open_after,
         (SELECT b.volume FROM bars_daily b
           WHERE b.symbol = l8.symbol AND b.bar_date = l8.reported_at
           LIMIT 1)                            AS volume_event,
         (SELECT AVG(sub.volume)
            FROM (SELECT volume FROM bars_daily b2
                   WHERE b2.symbol = l8.symbol AND b2.bar_date < l8.reported_at
                   ORDER BY b2.bar_date DESC LIMIT 20) AS sub) AS avg_vol_20d
  FROM last8 l8
),
per_event AS (
  SELECT symbol, rn,
         guidance_direction, gross_margin, operating_margin,
         implied_move_pct_pre_event,
         CASE WHEN close_before IS NOT NULL AND close_after IS NOT NULL AND close_before > 0
              THEN ABS(close_after - close_before) / close_before END   AS realized_move_pct,
         CASE WHEN close_after IS NOT NULL AND open_after IS NOT NULL
              THEN (close_after > open_after) END                        AS gap_held,
         CASE WHEN volume_event IS NOT NULL AND avg_vol_20d IS NOT NULL AND avg_vol_20d > 0
              THEN CAST(volume_event AS DOUBLE) / avg_vol_20d END        AS earnings_day_volume_ratio,
         CASE WHEN eps_estimate IS NOT NULL AND ABS(eps_estimate) > 0 AND eps_actual IS NOT NULL
              THEN (eps_actual - eps_estimate) / ABS(eps_estimate) * 100.0 END AS eps_surprise_pct,
         CASE WHEN rev_estimate IS NOT NULL AND ABS(rev_estimate) > 0 AND rev_actual IS NOT NULL
              THEN (rev_actual - rev_estimate) / ABS(rev_estimate) * 100.0 END AS revenue_surprise_pct
  FROM event_bars
),
aggregated AS (
  SELECT symbol,
         AVG(realized_move_pct)                                          AS avg_earnings_move_pct,
         CAST(COUNT(CASE WHEN realized_move_pct IS NOT NULL
                              AND implied_move_pct_pre_event IS NOT NULL
                              AND realized_move_pct > implied_move_pct_pre_event
                          THEN 1 END) AS DOUBLE)                          AS earnings_move_count_above_implied
  FROM per_event GROUP BY symbol
),
most_recent AS (SELECT * FROM per_event WHERE rn = 1),
prev_event  AS (SELECT * FROM per_event WHERE rn = 2),
next_cal AS (
  SELECT symbol, MIN(report_date) AS next_date
  FROM earnings_calendar WHERE report_date > CURRENT_DATE
  GROUP BY symbol
),
latest_reported AS (
  SELECT symbol, MAX(reported_at) AS last_date
  FROM earnings_events WHERE reported_at IS NOT NULL
  GROUP BY symbol
)
SELECT s.symbol,
       agg.avg_earnings_move_pct,
       agg.earnings_move_count_above_implied,
       CAST(DATEDIFF('day', lr.last_date, CURRENT_DATE) AS DOUBLE) AS days_since_earnings,
       CAST(DATEDIFF('day', CURRENT_DATE, nc.next_date)  AS DOUBLE) AS days_to_next_earnings,
       mr.gap_held                                                  AS gap_held_flag,
       mr.earnings_day_volume_ratio,
       CASE WHEN mr.gross_margin IS NOT NULL AND pv.gross_margin IS NOT NULL
            THEN mr.gross_margin - pv.gross_margin END              AS gross_margin_delta_qoq,
       CASE WHEN mr.operating_margin IS NOT NULL AND pv.operating_margin IS NOT NULL
            THEN mr.operating_margin - pv.operating_margin END      AS operating_margin_delta_qoq,
       mr.eps_surprise_pct, mr.revenue_surprise_pct, mr.guidance_direction
FROM all_symbols s
LEFT JOIN aggregated     agg ON agg.symbol = s.symbol
LEFT JOIN most_recent     mr ON  mr.symbol = s.symbol
LEFT JOIN prev_event      pv ON  pv.symbol = s.symbol
LEFT JOIN next_cal        nc ON  nc.symbol = s.symbol
LEFT JOIN latest_reported lr ON  lr.symbol = s.symbol;

-- :name beta-recompute :! :n
-- 252-day OLS beta vs `benchmark`. NULL when fewer than 252 paired obs.
INSERT INTO latest_indicators (symbol, beta_252d)
WITH
  sym_log_returns AS (
    SELECT symbol, bar_date,
           LN(close / LAG(close) OVER (PARTITION BY symbol ORDER BY bar_date)) AS log_ret
    FROM bars_daily WHERE close > 0
  ),
  bench AS (
    SELECT ret_date, ret_1d AS bench_ret
    FROM benchmark_returns
    WHERE benchmark = :benchmark AND ret_1d IS NOT NULL
  ),
  paired AS (
    SELECT s.symbol, s.bar_date, s.log_ret, b.bench_ret
    FROM sym_log_returns s
    JOIN bench b ON s.bar_date = b.ret_date
    WHERE s.log_ret IS NOT NULL
  ),
  ranked AS (
    SELECT symbol, bar_date, log_ret, bench_ret,
           ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY bar_date DESC) AS rn
    FROM paired
  ),
  betas AS (
    SELECT symbol,
           CASE WHEN COUNT(*) >= 252
                THEN regr_slope(log_ret, bench_ret)
                ELSE NULL END AS beta_252d
    FROM ranked WHERE rn <= 252
    GROUP BY symbol
  )
SELECT symbol, beta_252d FROM betas
ON CONFLICT (symbol) DO UPDATE SET beta_252d = excluded.beta_252d;

-- :name iv-window :? :*
WITH
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
LEFT JOIN w63  ON l.symbol = w63.symbol;
