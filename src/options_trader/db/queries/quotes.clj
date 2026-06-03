(ns options-trader.db.queries.quotes
  (:require [hugsql.core :as hugsql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private as-lower {:builder-fn rs/as-unqualified-lower-maps})

(hugsql/def-sqlvec-fns "sql/quotes.sql")

(defn avg-volume [ds symbol n-days]
  (-> (jdbc/execute-one! ds (avg-volume-sqlvec {:symbol symbol :n-days n-days}) as-lower)
      :avg))

(defn pc-ratios [ds symbol]
  (let [row (jdbc/execute-one! ds (pc-ratios-sqlvec {:symbol symbol}) as-lower)]
    {:pc-oi-ratio (:oi row) :pc-vol-ratio (:vol row)}))

(defn vwap-from-bars-intraday [ds symbol]
  (let [row (jdbc/execute-one! ds (vwap-from-bars-intraday-sqlvec {:symbol symbol}) as-lower)
        num (:num row) den (:den row)]
    (when (and (number? num) (number? den) (pos? den))
      (double (/ num den)))))
