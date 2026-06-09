(ns options-trader.db.queries.watchlist
  "DuckDB-backed lookups for the live watchlist panel.
   SQL: resources/sql/watchlist.sql."
  (:require [hugsql.core :as hugsql]
            [options-trader.util :refer [query-scalar]]))

(hugsql/def-sqlvec-fns "sql/watchlist.sql")

(defn avg-daily-volume
  "Mean daily volume for `symbol` across the most recent `window` bars,
   or nil when bars_daily is empty for that symbol."
  [ds symbol window]
  (query-scalar ds
    (avg-daily-volume-sqlvec {:symbol symbol :window window})
    :avg_vol))

(defn latest-daily-volume
  "Most recent completed-day volume for `symbol` in raw shares, or nil
   when bars_daily has no rows."
  [ds symbol]
  (query-scalar ds
    (latest-daily-volume-sqlvec {:symbol symbol})
    :vol))
