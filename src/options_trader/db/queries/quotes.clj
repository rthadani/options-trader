(ns options-trader.db.queries.quotes
  (:require [hugsql.core :as hugsql]
            [next.jdbc :as jdbc]
            [options-trader.util :refer [as-lower query-scalar]]))

(hugsql/def-sqlvec-fns "sql/quotes.sql")

(defn avg-volume [ds symbol n-days]
  (query-scalar ds (avg-volume-sqlvec {:symbol symbol :n-days n-days}) :avg))

(defn pc-ratios [ds symbol]
  (let [row (jdbc/execute-one! ds (pc-ratios-sqlvec {:symbol symbol}) as-lower)]
    {:pc-oi-ratio (:oi row) :pc-vol-ratio (:vol row)}))

(defn vwap-from-bars-intraday [ds symbol]
  (let [row (jdbc/execute-one! ds (vwap-from-bars-intraday-sqlvec {:symbol symbol}) as-lower)
        num (:num row) den (:den row)]
    (when (and (number? num) (number? den) (pos? den))
      (double (/ num den)))))
