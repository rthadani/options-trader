-- Quote-related read queries. Loaded by db.queries.quotes.

-- :name avg-volume :? :1
SELECT AVG(volume) AS avg FROM (
  SELECT volume FROM bars_daily WHERE symbol = :symbol
   ORDER BY bar_date DESC LIMIT :n-days
);

-- :name pc-ratios :? :1
SELECT put_call_oi_ratio     AS oi,
       put_call_volume_ratio AS vol
  FROM latest_indicators
 WHERE symbol = :symbol;

-- :name vwap-from-bars-intraday :? :1
SELECT SUM(((high + low + close) / 3.0) * volume) AS num,
       SUM(volume)                                AS den
  FROM bars_intraday
 WHERE symbol  = :symbol
   AND bar_ts >= CURRENT_DATE
   AND volume IS NOT NULL AND volume > 0;
