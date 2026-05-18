(ns options-trader.portfolio.views-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.portfolio.views :as views]))

(defn- make-test-ds []
  (Class/forName "org.duckdb.DuckDBDriver")
  (let [conn (java.sql.DriverManager/getConnection "jdbc:duckdb:")]
    (jdbc/execute! conn
      ["CREATE TABLE positions (
          account    VARCHAR NOT NULL,
          symbol     VARCHAR NOT NULL,
          opt_right  VARCHAR NOT NULL DEFAULT '',
          expiry     DATE    NOT NULL DEFAULT '1900-01-01',
          strike     DOUBLE  NOT NULL DEFAULT 0.0,
          quantity   INTEGER NOT NULL,
          avg_cost   DOUBLE,
          market_val DOUBLE,
          unrealized DOUBLE,
          updated_at TIMESTAMP DEFAULT current_timestamp,
          PRIMARY KEY (account, symbol, opt_right, expiry, strike)
        )"])
    (jdbc/execute! conn
      ["CREATE TABLE account_summary (
          account      VARCHAR   NOT NULL,
          fetched_at   TIMESTAMP NOT NULL,
          net_liq      DOUBLE,
          cash         DOUBLE,
          buying_power DOUBLE,
          day_pl       DOUBLE,
          data         JSON,
          PRIMARY KEY (account, fetched_at)
        )"])
    (jdbc/execute! conn
      ["CREATE TABLE option_chain (
          symbol        VARCHAR   NOT NULL,
          expiry        DATE      NOT NULL,
          strike        DOUBLE    NOT NULL,
          opt_right     VARCHAR   NOT NULL,
          fetched_at    TIMESTAMP NOT NULL,
          bid           DOUBLE,
          ask           DOUBLE,
          last          DOUBLE,
          volume        INTEGER,
          open_interest INTEGER,
          iv            DOUBLE,
          delta         DOUBLE,
          gamma         DOUBLE,
          theta         DOUBLE,
          vega          DOUBLE,
          PRIMARY KEY (symbol, expiry, strike, opt_right, fetched_at)
        )"])
    conn))

(defn- seed-data! [ds]
  (jdbc/execute! ds
    ["INSERT INTO account_summary (account, fetched_at, net_liq, cash, buying_power, day_pl)
      VALUES ('DU123', current_timestamp, 100000.0, 20000.0, 80000.0, 350.0)"])
  (jdbc/execute! ds
    ["INSERT INTO positions (account, symbol, opt_right, quantity, avg_cost, market_val, unrealized)
      VALUES ('DU123', 'AAPL', '', 100, 185.50, 19000.0, 450.0)"])
  (jdbc/execute! ds
    ["INSERT INTO positions
        (account, symbol, opt_right, expiry, strike, quantity, avg_cost, market_val, unrealized)
      VALUES ('DU123', 'AAPL', 'P', '2025-06-20', 180.0, -1, 3.50, -250.0, 100.0)"])
  (jdbc/execute! ds
    ["INSERT INTO option_chain
        (symbol, expiry, strike, opt_right, fetched_at, delta, gamma, theta, vega, iv)
      VALUES ('AAPL', '2025-06-20', 180.0, 'P', current_timestamp, -0.40, 0.02, -0.05, 0.15, 0.28)"]))

(deftest create-views-no-throw-test
  (testing "create-views! completes without error"
    (let [ds (make-test-ds)]
      (views/create-views! ds)
      (is true "create-views! completed"))))

(deftest portfolio-summary-empty-test
  (testing "portfolio_summary view returns empty vector when no data"
    (let [ds (make-test-ds)]
      (views/create-views! ds)
      (is (vector? (views/query-portfolio-summary ds)))
      (is (empty? (views/query-portfolio-summary ds))))))

(deftest portfolio-summary-columns-test
  (testing "portfolio_summary view returns expected columns"
    (let [ds (make-test-ds)]
      (seed-data! ds)
      (views/create-views! ds)
      (let [rows (views/query-portfolio-summary ds)]
        (is (= 1 (count rows)))
        (let [row (first rows)]
          (is (= "DU123" (:account row)))
          (is (= 100000.0 (:net_liq row)))
          (is (= 20000.0 (:cash row)))
          (is (number? (:total_market_val row)))
          (is (pos? (:position_count row))))))))

(deftest portfolio-greeks-empty-test
  (testing "portfolio_greeks view returns empty vector when no option positions"
    (let [ds (make-test-ds)]
      (views/create-views! ds)
      (is (vector? (views/query-portfolio-greeks ds)))
      (is (empty? (views/query-portfolio-greeks ds))))))

(deftest portfolio-greeks-joins-options-test
  (testing "portfolio_greeks view joins positions with option_chain greeks"
    (let [ds (make-test-ds)]
      (seed-data! ds)
      (views/create-views! ds)
      (let [rows (views/query-portfolio-greeks ds)]
        (is (= 1 (count rows)))
        (let [row (first rows)]
          (is (= "AAPL" (:symbol row)))
          (is (= "P" (:opt_right row)))
          (is (= -0.40 (:delta row)))
          (is (number? (:position_delta row)))
          (is (number? (:position_theta row))))))))

(deftest portfolio-greeks-stock-excluded-test
  (testing "stock positions (opt_right='') are excluded from portfolio_greeks"
    (let [ds (make-test-ds)]
      (seed-data! ds)
      (views/create-views! ds)
      (let [rows (views/query-portfolio-greeks ds)
            syms (map :symbol rows)]
        (is (every? #(= "AAPL" %) syms))
        (is (every? #(not= "" (:opt_right %)) rows))))))

(deftest create-views-idempotent-test
  (testing "calling create-views! twice does not throw"
    (let [ds (make-test-ds)]
      (views/create-views! ds)
      (views/create-views! ds)
      (is true "second create-views! did not throw"))))

(deftest portfolio-summary-sql-is-string-test
  (testing "portfolio-summary-sql is a non-blank string"
    (is (string? views/portfolio-summary-sql))
    (is (seq views/portfolio-summary-sql))))

(deftest portfolio-greeks-sql-is-string-test
  (testing "portfolio-greeks-sql is a non-blank string"
    (is (string? views/portfolio-greeks-sql))
    (is (seq views/portfolio-greeks-sql))))
