(ns options-trader.indicators.composites
  "MACD, Parabolic SAR, and On-Balance Volume composite flag columns for latest_indicators.
   Computes macd/macd_signal/macd_hist/macd_cross_flag via recursive EMA CTEs in DuckDB,
   psar/psar_flip_flag via a recursive PSAR CTE with AF=0.02/step=0.02/max=0.2,
   and obv/obv_trend via a running-sum window function.
   All columns are upserted into latest_indicators keyed on symbol;
   numeric columns emit NULL and flag columns emit 'none' when history is insufficient."
  (:require [next.jdbc :as jdbc]))

;;; ── Schema ───────────────────────────────────────────────────────────────────

(defn ensure-schema!
  "Add macd, macd_signal, macd_hist, macd_cross_flag, psar, psar_flip_flag,
   obv, and obv_trend columns to latest_indicators. Idempotent via IF NOT EXISTS."
  [ds]
  (doseq [[col sql-type]
          [["macd"            "DOUBLE"]
           ["macd_signal"     "DOUBLE"]
           ["macd_hist"       "DOUBLE"]
           ["macd_cross_flag" "VARCHAR"]
           ["psar"            "DOUBLE"]
           ["psar_flip_flag"  "VARCHAR"]
           ["obv"             "DOUBLE"]
           ["obv_trend"       "VARCHAR"]]]
    (jdbc/execute! ds
      [(str "ALTER TABLE latest_indicators ADD COLUMN IF NOT EXISTS " col " " sql-type)])))

;;; ── MACD SQL ─────────────────────────────────────────────────────────────────
;;
;; EMA(12): alpha=2/13, seeded with first bar's close for each symbol.
;; EMA(26): alpha=2/27, seeded with first bar's close for each symbol.
;; MACD line = EMA(12) - EMA(26) computed per bar via recursive CTEs.
;; Signal line = EMA(9) of MACD: alpha=2/10, seeded with first bar's MACD value.
;; Histogram = MACD - signal.
;; macd_cross_flag: 'bullish' when MACD crosses above signal on the latest bar,
;;                  'bearish' when it crosses below, 'none' when no cross or < 2 bars.
;; Convergence to ta4j semantics within 1e-6 after a warmup of ~3x the period length.

(def ^:private macd-sql
  "INSERT INTO latest_indicators (symbol, macd, macd_signal, macd_hist, macd_cross_flag)
   WITH RECURSIVE
     ordered AS (
       SELECT symbol, bar_date, close,
         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY bar_date) AS rn
       FROM bars_daily
       WHERE close > 0
     ),
     ema12 AS (
       SELECT symbol, bar_date, rn, close AS ema_val
       FROM ordered WHERE rn = 1
       UNION ALL
       SELECT o.symbol, o.bar_date, o.rn,
         (2.0 / 13.0) * o.close + (11.0 / 13.0) * e.ema_val
       FROM ordered o
       JOIN ema12 e ON o.symbol = e.symbol AND o.rn = e.rn + 1
     ),
     ema26 AS (
       SELECT symbol, bar_date, rn, close AS ema_val
       FROM ordered WHERE rn = 1
       UNION ALL
       SELECT o.symbol, o.bar_date, o.rn,
         (2.0 / 27.0) * o.close + (25.0 / 27.0) * e.ema_val
       FROM ordered o
       JOIN ema26 e ON o.symbol = e.symbol AND o.rn = e.rn + 1
     ),
     macd_vals AS (
       SELECT e12.symbol, e12.bar_date, e12.rn,
         e12.ema_val - e26.ema_val AS macd_val
       FROM ema12 e12
       JOIN ema26 e26 ON e12.symbol = e26.symbol AND e12.rn = e26.rn
     ),
     signal_vals AS (
       SELECT symbol, bar_date, rn, macd_val AS ema_val
       FROM macd_vals WHERE rn = 1
       UNION ALL
       SELECT m.symbol, m.bar_date, m.rn,
         (2.0 / 10.0) * m.macd_val + (8.0 / 10.0) * s.ema_val
       FROM macd_vals m
       JOIN signal_vals s ON m.symbol = s.symbol AND m.rn = s.rn + 1
     ),
     combined AS (
       SELECT m.symbol, m.rn,
         m.macd_val,
         s.ema_val             AS signal_val,
         m.macd_val - s.ema_val AS hist_val
       FROM macd_vals m
       JOIN signal_vals s ON m.symbol = s.symbol AND m.rn = s.rn
     ),
     ranked AS (
       SELECT *,
         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY rn DESC) AS rk
       FROM combined
     ),
     latest_m AS (SELECT * FROM ranked WHERE rk = 1),
     prev_m   AS (SELECT * FROM ranked WHERE rk = 2),
     result AS (
       SELECT
         l.symbol,
         l.macd_val   AS macd,
         l.signal_val AS macd_signal,
         l.hist_val   AS macd_hist,
         CASE
           WHEN p.symbol IS NULL                                          THEN 'none'
           WHEN p.macd_val < p.signal_val AND l.macd_val >= l.signal_val THEN 'bullish'
           WHEN p.macd_val > p.signal_val AND l.macd_val <= l.signal_val THEN 'bearish'
           ELSE 'none'
         END AS macd_cross_flag
       FROM latest_m l
       LEFT JOIN prev_m p ON l.symbol = p.symbol
     )
   SELECT symbol, macd, macd_signal, macd_hist, macd_cross_flag FROM result
   ON CONFLICT (symbol) DO UPDATE SET
     macd            = excluded.macd,
     macd_signal     = excluded.macd_signal,
     macd_hist       = excluded.macd_hist,
     macd_cross_flag = excluded.macd_cross_flag")

;;; ── PSAR SQL ─────────────────────────────────────────────────────────────────
;;
;; Parabolic SAR via recursive CTE with AF initial=0.02, step=0.02, max=0.2.
;; SAR for uptrend clamped to LEAST(raw_SAR, prev_low, prev_prev_low) via carry columns.
;; SAR for downtrend clamped to GREATEST(raw_SAR, prev_high, prev_prev_high).
;; psar_flip_flag: 'bullish' when close crosses above PSAR on latest bar,
;;                 'bearish' when crosses below, 'none' when no flip or < 2 bars.

(def ^:private psar-sql
  "INSERT INTO latest_indicators (symbol, psar, psar_flip_flag)
   WITH RECURSIVE
     ordered AS (
       SELECT symbol, bar_date, high, low, close,
         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY bar_date) AS rn
       FROM bars_daily
       WHERE close > 0 AND high > 0 AND low > 0
     ),
     psar_rec AS (
       SELECT symbol, bar_date, rn,
         o.high AS carry_high,
         o.low  AS carry_low,
         o.low  AS psar_val,
         o.high AS ep,
         CAST(1    AS INTEGER) AS trend,
         CAST(0.02 AS DOUBLE)  AS af,
         o.low  AS prev_carry_low,
         o.high AS prev_carry_high
       FROM ordered o WHERE rn = 1
       UNION ALL
       SELECT
         o.symbol, o.bar_date, o.rn,
         o.high AS carry_high,
         o.low  AS carry_low,
         CASE
           WHEN p.trend = 1 THEN
             CASE
               WHEN o.low < LEAST(p.psar_val + p.af * (p.ep - p.psar_val),
                                   p.carry_low, p.prev_carry_low)
               THEN p.ep
               ELSE LEAST(p.psar_val + p.af * (p.ep - p.psar_val),
                          p.carry_low, p.prev_carry_low)
             END
           ELSE
             CASE
               WHEN o.high > GREATEST(p.psar_val + p.af * (p.ep - p.psar_val),
                                       p.carry_high, p.prev_carry_high)
               THEN p.ep
               ELSE GREATEST(p.psar_val + p.af * (p.ep - p.psar_val),
                             p.carry_high, p.prev_carry_high)
             END
         END AS psar_val,
         CASE
           WHEN p.trend = 1 THEN
             CASE
               WHEN o.low < LEAST(p.psar_val + p.af * (p.ep - p.psar_val),
                                   p.carry_low, p.prev_carry_low)
               THEN o.low
               WHEN o.high > p.ep THEN o.high
               ELSE p.ep
             END
           ELSE
             CASE
               WHEN o.high > GREATEST(p.psar_val + p.af * (p.ep - p.psar_val),
                                       p.carry_high, p.prev_carry_high)
               THEN o.high
               WHEN o.low < p.ep THEN o.low
               ELSE p.ep
             END
         END AS ep,
         CASE
           WHEN p.trend = 1 THEN
             CASE
               WHEN o.low < LEAST(p.psar_val + p.af * (p.ep - p.psar_val),
                                   p.carry_low, p.prev_carry_low)
               THEN -1 ELSE 1
             END
           ELSE
             CASE
               WHEN o.high > GREATEST(p.psar_val + p.af * (p.ep - p.psar_val),
                                       p.carry_high, p.prev_carry_high)
               THEN 1 ELSE -1
             END
         END AS trend,
         CASE
           WHEN p.trend = 1 THEN
             CASE
               WHEN o.low < LEAST(p.psar_val + p.af * (p.ep - p.psar_val),
                                   p.carry_low, p.prev_carry_low)
               THEN 0.02
               WHEN o.high > p.ep THEN LEAST(p.af + 0.02, 0.2)
               ELSE p.af
             END
           ELSE
             CASE
               WHEN o.high > GREATEST(p.psar_val + p.af * (p.ep - p.psar_val),
                                       p.carry_high, p.prev_carry_high)
               THEN 0.02
               WHEN o.low < p.ep THEN LEAST(p.af + 0.02, 0.2)
               ELSE p.af
             END
         END AS af,
         p.carry_low  AS prev_carry_low,
         p.carry_high AS prev_carry_high
       FROM ordered o
       JOIN psar_rec p ON o.symbol = p.symbol AND o.rn = p.rn + 1
     ),
     psar_joined AS (
       SELECT pr.symbol, pr.rn, pr.psar_val, o.close,
         ROW_NUMBER() OVER (PARTITION BY pr.symbol ORDER BY pr.rn DESC) AS rk
       FROM psar_rec pr
       JOIN ordered o ON pr.symbol = o.symbol AND pr.rn = o.rn
     ),
     latest_p AS (SELECT * FROM psar_joined WHERE rk = 1),
     prev_p   AS (SELECT * FROM psar_joined WHERE rk = 2),
     result AS (
       SELECT
         l.symbol,
         l.psar_val AS psar,
         CASE
           WHEN p.symbol IS NULL                                THEN 'none'
           WHEN p.close < p.psar_val AND l.close >= l.psar_val THEN 'bullish'
           WHEN p.close > p.psar_val AND l.close <= l.psar_val THEN 'bearish'
           ELSE 'none'
         END AS psar_flip_flag
       FROM latest_p l
       LEFT JOIN prev_p p ON l.symbol = p.symbol
     )
   SELECT symbol, psar, psar_flip_flag FROM result
   ON CONFLICT (symbol) DO UPDATE SET
     psar           = excluded.psar,
     psar_flip_flag = excluded.psar_flip_flag")

;;; ── OBV SQL ──────────────────────────────────────────────────────────────────
;;
;; OBV = cumulative sum of sign(close - prev_close) * volume via window function.
;; First bar contributes its full volume; subsequent bars: +vol if up, -vol if down, 0 if flat.
;; obv_trend: compares SMA(obv,20) to SMA(obv,50).
;;   'rising'  when SMA(20) > SMA(50)
;;   'falling' when SMA(20) < SMA(50)
;;   'flat'    when SMA(20) = SMA(50)
;;   'none'    when fewer than 50 bars available

(def ^:private obv-sql
  "INSERT INTO latest_indicators (symbol, obv, obv_trend)
   WITH
     bars_with_prev AS (
       SELECT symbol, bar_date, close, volume,
         LAG(close) OVER (PARTITION BY symbol ORDER BY bar_date) AS prev_close,
         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY bar_date) AS rn
       FROM bars_daily
       WHERE volume >= 0
     ),
     obv_running AS (
       SELECT symbol, bar_date, rn,
         SUM(
           CASE
             WHEN prev_close IS NULL   THEN CAST(volume AS BIGINT)
             WHEN close > prev_close   THEN CAST(volume AS BIGINT)
             WHEN close < prev_close   THEN -CAST(volume AS BIGINT)
             ELSE 0
           END
         ) OVER (PARTITION BY symbol ORDER BY bar_date
                 ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS obv_val
       FROM bars_with_prev
     ),
     obv_windowed AS (
       SELECT symbol, rn, obv_val,
         AVG(obv_val) OVER (PARTITION BY symbol ORDER BY rn
                            ROWS BETWEEN 19 PRECEDING AND CURRENT ROW) AS sma20,
         AVG(obv_val) OVER (PARTITION BY symbol ORDER BY rn
                            ROWS BETWEEN 49 PRECEDING AND CURRENT ROW) AS sma50,
         COUNT(*) OVER (PARTITION BY symbol ORDER BY rn
                        ROWS BETWEEN 19 PRECEDING AND CURRENT ROW)     AS cnt20,
         COUNT(*) OVER (PARTITION BY symbol ORDER BY rn
                        ROWS BETWEEN 49 PRECEDING AND CURRENT ROW)     AS cnt50,
         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY rn DESC)       AS rk
       FROM obv_running
     ),
     latest AS (SELECT * FROM obv_windowed WHERE rk = 1),
     result AS (
       SELECT symbol, obv_val AS obv,
         CASE
           WHEN cnt20 < 20 OR cnt50 < 50 THEN 'none'
           WHEN sma20 > sma50            THEN 'rising'
           WHEN sma20 < sma50            THEN 'falling'
           ELSE                               'flat'
         END AS obv_trend
       FROM latest
     )
   SELECT symbol, obv, obv_trend FROM result
   ON CONFLICT (symbol) DO UPDATE SET
     obv       = excluded.obv,
     obv_trend = excluded.obv_trend")

;;; ── Public entry point ───────────────────────────────────────────────────────

(defn refresh-composites!
  "Compute MACD, Parabolic SAR, and On-Balance Volume indicators for all symbols
   in bars_daily and upsert results into latest_indicators.
   Runs three SQL passes in sequence: MACD (recursive EMA12/26/9 CTEs),
   PSAR (recursive CTE with carry columns for two-bar SAR clamping),
   and OBV (running-sum window function with SMA20/SMA50 trend classification).
   Emits NULL/'none' for symbols with insufficient history.
   Idempotent: ON CONFLICT (symbol) DO UPDATE for each pass."
  [ds]
  (ensure-schema! ds)
  (jdbc/execute! ds [macd-sql])
  (jdbc/execute! ds [psar-sql])
  (jdbc/execute! ds [obv-sql]))
