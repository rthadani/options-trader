(ns options-trader.portfolio.views
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def portfolio-summary-sql
  "CREATE OR REPLACE VIEW portfolio_summary AS
   SELECT
     a.account,
     a.net_liq,
     a.cash,
     a.buying_power,
     a.day_pl,
     COALESCE(SUM(p.market_val), 0.0) AS total_market_val,
     COALESCE(SUM(p.unrealized), 0.0) AS total_unrealized,
     COUNT(p.symbol)                  AS position_count
   FROM (
     SELECT account, net_liq, cash, buying_power, day_pl,
            ROW_NUMBER() OVER (PARTITION BY account ORDER BY fetched_at DESC) AS rn
     FROM account_summary
   ) a
   LEFT JOIN positions p ON p.account = a.account
   WHERE a.rn = 1
   GROUP BY a.account, a.net_liq, a.cash, a.buying_power, a.day_pl")

(def portfolio-greeks-sql
  "CREATE OR REPLACE VIEW portfolio_greeks AS
   SELECT
     p.account,
     p.symbol,
     p.opt_right,
     p.expiry,
     p.strike,
     p.quantity,
     oc.delta,
     oc.gamma,
     oc.theta,
     oc.vega,
     oc.iv,
     p.quantity * COALESCE(oc.delta, 0.0) * 100 AS position_delta,
     p.quantity * COALESCE(oc.gamma, 0.0) * 100 AS position_gamma,
     p.quantity * COALESCE(oc.theta, 0.0) * 100 AS position_theta,
     p.quantity * COALESCE(oc.vega,  0.0) * 100 AS position_vega
   FROM positions p
   JOIN (
     SELECT symbol, expiry, strike, opt_right, delta, gamma, theta, vega, iv,
            ROW_NUMBER() OVER (PARTITION BY symbol, expiry, strike, opt_right
                               ORDER BY fetched_at DESC) AS rn
     FROM option_chain
   ) oc
     ON oc.symbol    = p.symbol
    AND oc.expiry    = p.expiry
    AND oc.strike    = p.strike
    AND oc.opt_right = p.opt_right
   WHERE oc.rn = 1
     AND p.opt_right != ''")

(defn create-views!
  "Create portfolio_summary and portfolio_greeks views in ds.
   Idempotent — uses CREATE OR REPLACE VIEW."
  [ds]
  (jdbc/execute! ds [portfolio-summary-sql])
  (jdbc/execute! ds [portfolio-greeks-sql]))

(defn query-portfolio-summary
  "Return all rows from portfolio_summary view as a vector of maps."
  [ds]
  (jdbc/execute! ds
    ["SELECT * FROM portfolio_summary"]
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn query-portfolio-greeks
  "Return all rows from portfolio_greeks view as a vector of maps."
  [ds]
  (jdbc/execute! ds
    ["SELECT * FROM portfolio_greeks"]
    {:builder-fn rs/as-unqualified-lower-maps}))
