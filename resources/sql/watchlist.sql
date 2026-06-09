-- Reads for the live watchlist panel. Only the AVG-volume lookup needs
-- the warehouse; the rest of the watchlist is live tick data.

-- :name avg-daily-volume :? :1
-- Mean daily volume over the most recent :window bars for :symbol. nil
-- when bars_daily has no rows for the symbol.
SELECT AVG(volume) AS avg_vol
  FROM (SELECT volume FROM bars_daily
         WHERE symbol = :symbol
         ORDER BY bar_date DESC LIMIT :window) sub;

-- :name latest-daily-volume :? :1
-- Most recent day's full session volume for :symbol in raw shares.
-- We source VOL from the warehouse instead of IB's live ticks because
-- IB's RT_VOLUME resets per session (regular vs extended hours), and
-- tick-size field 8 reports in per-contract lot units whose multiplier
-- varies by stock — neither is reliable for an at-a-glance VOL column.
SELECT volume AS vol
  FROM bars_daily
 WHERE symbol = :symbol
 ORDER BY bar_date DESC LIMIT 1;
