(ns options-trader.db.queries.portfolio
  (:require [hugsql.core :as hugsql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private as-lower {:builder-fn rs/as-unqualified-lower-maps})

(hugsql/def-sqlvec-fns "sql/portfolio.sql")

(defn upsert-position! [ds row]
  (jdbc/execute-one! ds (upsert-position-sqlvec row)))

(defn insert-account-summary! [ds row]
  (jdbc/execute-one! ds (insert-account-summary-sqlvec row)))

(defn select-positions [ds account]
  (jdbc/execute! ds (select-positions-sqlvec {:account account}) as-lower))

(defn select-account-summary [ds account]
  (jdbc/execute-one! ds (select-account-summary-sqlvec {:account account}) as-lower))

(defn default-account-from-positions [ds]
  (-> (jdbc/execute-one! ds (default-account-from-positions-sqlvec) as-lower) :account))

(defn default-account-from-summary [ds]
  (-> (jdbc/execute-one! ds (default-account-from-summary-sqlvec) as-lower) :account))

(defn query-portfolio-summary [ds]
  (jdbc/execute! ds (select-portfolio-summary-sqlvec) as-lower))

(defn query-portfolio-greeks [ds]
  (jdbc/execute! ds (select-portfolio-greeks-sqlvec) as-lower))

(defn create-views! [ds]
  (jdbc/execute-one! ds (create-portfolio-summary-view-sqlvec))
  (jdbc/execute-one! ds (create-portfolio-greeks-view-sqlvec)))
