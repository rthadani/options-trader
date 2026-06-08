(ns options-trader.db.queries.research-cache
  "DuckDB-backed lookups for the cache-first MCP tools.
   SQL: resources/sql/research_cache.sql."
  (:require [hugsql.core :as hugsql]
            [next.jdbc :as jdbc]
            [options-trader.util :refer [as-lower query-scalar]]))

(hugsql/def-sqlvec-fns "sql/research_cache.sql")

(defn latest-fundamentals
  "Raw `data` JSON string for the most recent fundamentals row, or nil."
  [ds symbol]
  (query-scalar ds (latest-fundamentals-sqlvec {:symbol symbol}) :data))

(defn latest-news [ds symbol limit]
  (jdbc/execute! ds (latest-news-sqlvec {:symbol symbol :limit limit}) as-lower))

(defn latest-news-titles [ds symbol limit]
  (jdbc/execute! ds (latest-news-titles-sqlvec {:symbol symbol :limit limit}) as-lower))

(defn latest-filings
  "Filings for symbol, filtered by optional :form-type / :start-date /
   :end-date. The .sql file's --~ branches include each predicate only
   when its key is present in `params`."
  [ds symbol params]
  (jdbc/execute! ds
    (latest-filings-sqlvec (merge {:symbol symbol :limit 50} params))
    as-lower))

(defn latest-earnings-history [ds symbol limit]
  (jdbc/execute! ds
    (latest-earnings-history-sqlvec {:symbol symbol :limit limit})
    as-lower))

(defn earnings-calendar-range [ds {:keys [start end]}]
  (jdbc/execute! ds
    (earnings-calendar-range-sqlvec {:start start :end end})
    as-lower))

(defn latest-short-interest [ds symbol limit]
  (jdbc/execute! ds
    (latest-short-interest-sqlvec {:symbol symbol :limit limit})
    as-lower))
