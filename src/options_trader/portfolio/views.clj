(ns options-trader.portfolio.views
  "Re-exports portfolio view DDL + queries from db.queries.portfolio. The
   SQL itself lives in resources/sql/portfolio.sql; this namespace just
   surfaces the API at the historical name."
  (:require [options-trader.db.queries.portfolio :as q]))

(defn create-views!
  "Create portfolio_summary and portfolio_greeks views. Idempotent."
  [ds]
  (q/create-views! ds))

(defn query-portfolio-summary [ds]
  (q/query-portfolio-summary ds))

(defn query-portfolio-greeks [ds]
  (q/query-portfolio-greeks ds))
