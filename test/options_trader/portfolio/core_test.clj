(ns options-trader.portfolio.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.data.ibkr :as ibkr]
            [options-trader.portfolio.core :as portfolio]))

(defn- make-test-ds []
  (Class/forName "org.duckdb.DuckDBDriver")
  (let [conn (java.sql.DriverManager/getConnection "jdbc:duckdb:")]
    (jdbc/execute! conn
      ["CREATE TABLE IF NOT EXISTS positions (
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
      ["CREATE TABLE IF NOT EXISTS account_summary (
          account      VARCHAR   NOT NULL,
          fetched_at   TIMESTAMP NOT NULL,
          net_liq      DOUBLE,
          cash         DOUBLE,
          buying_power DOUBLE,
          day_pl       DOUBLE,
          data         JSON,
          PRIMARY KEY (account, fetched_at)
        )"])
    conn))

(def ^:private test-position
  {:symbol "TSLA" :opt-right "" :expiry (java.time.LocalDate/parse "1900-01-01")
   :strike 0.0 :qty 10 :avg-cost 250.0 :market-value 2500.0 :unrealized-pnl 0.0})

(def ^:private test-summary
  {:net-liq 50000.0 :cash 10000.0 :buying-power 40000.0 :day-pl 100.0})

(defmacro ^:private with-mock-ibkr
  "Stub ibkr/req-positions and ibkr/req-account-summary so they invoke the
   callback with the given canned data, then a terminal nil — matching the
   stream-mode contract IbkrSource expects."
  [positions-data summary-data & body]
  `(with-redefs [ibkr/req-positions
                 (fn [_conn# cb#]
                   (doseq [p# ~positions-data] (cb# p#))
                   (cb# nil)
                   1)
                 ibkr/req-account-summary
                 (fn [_conn# _tags# cb#]
                   (cb# ~summary-data)
                   (cb# nil)
                   2)]
     ~@body))

(deftest mock-source-positions-test
  (testing "MockSource returns a non-empty seq of position maps"
    (let [src (portfolio/->MockSource)
          pos (portfolio/positions src)]
      (is (seq pos))
      (is (every? :symbol pos))
      (is (every? :qty pos)))))

(deftest mock-source-account-summary-test
  (testing "MockSource returns a non-nil account summary map"
    (let [src  (portfolio/->MockSource)
          summ (portfolio/account-summary src)]
      (is (map? summ))
      (is (pos? (:net-liq summ)))
      (is (number? (:cash summ)))
      (is (number? (:buying-power summ))))))

(deftest mock-source-realized-pnl-test
  (testing "MockSource realized-pnl returns a number"
    (let [src (portfolio/->MockSource)]
      (is (number? (portfolio/realized-pnl src 0 Long/MAX_VALUE))))))

(deftest mock-source-expiry-is-local-date-test
  (testing "MockSource option position expiry is a java.time.LocalDate"
    (let [src     (portfolio/->MockSource)
          pos     (portfolio/positions src)
          opt-pos (first (filter #(= "P" (:opt-right %)) pos))]
      (is (some? opt-pos))
      (is (instance? java.time.LocalDate (:expiry opt-pos))))))

(deftest refresh!-populates-positions-test
  (testing "refresh! with MockSource upserts positions into DB"
    (let [ds     (make-test-ds)
          src    (portfolio/->MockSource)
          result (portfolio/refresh! src ds "DU123456")]
      (is (pos? (:positions result)))
      (is (= "DU123456" (:account-id result)))
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM positions WHERE account = 'DU123456'"]
                   {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= (:positions result) (count rows)))))))

(deftest refresh!-populates-account-summary-test
  (testing "refresh! with MockSource upserts account_summary into DB"
    (let [ds  (make-test-ds)
          src (portfolio/->MockSource)]
      (portfolio/refresh! src ds "DU123456")
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM account_summary WHERE account = 'DU123456'"]
                   {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 1 (count rows)))
        (is (pos? (-> rows first :net_liq)))))))

(deftest refresh!-idempotent-test
  (testing "calling refresh! twice does not duplicate positions"
    (let [ds  (make-test-ds)
          src (portfolio/->MockSource)]
      (portfolio/refresh! src ds "DU123456")
      (portfolio/refresh! src ds "DU123456")
      (let [rows (jdbc/execute! ds
                   ["SELECT COUNT(*) AS cnt FROM positions WHERE account = 'DU123456'"]
                   {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= (count (portfolio/positions src))
               (:cnt (first rows))))))))

(deftest ibkr-source-calls-req-positions-test
  (testing "IbkrSource.positions calls ibkr/req-positions and returns collected data"
    (with-mock-ibkr [test-position] test-summary
      (let [ds  (make-test-ds)
            src (portfolio/->IbkrSource :stub-conn ds "DU999")
            pos (portfolio/positions src)]
        (is (= 1 (count pos)))
        (is (= "TSLA" (:symbol (first pos))))
        (is (= 10 (:qty (first pos))))))))

(deftest ibkr-source-calls-req-account-summary-test
  (testing "IbkrSource.account-summary calls ibkr/req-account-summary and returns data"
    (with-mock-ibkr [test-position] test-summary
      (let [ds  (make-test-ds)
            src (portfolio/->IbkrSource :stub-conn ds "DU999")
            s   (portfolio/account-summary src)]
        (is (= 50000.0 (:net-liq s)))
        (is (= 10000.0 (:cash s)))))))

(deftest ibkr-source-upserts-positions-to-db-test
  (testing "IbkrSource.positions persists fetched data to DB via IPortfolioStore"
    (with-mock-ibkr [test-position] test-summary
      (let [ds  (make-test-ds)
            src (portfolio/->IbkrSource :stub-conn ds "DU999")]
        (portfolio/positions src)
        (let [rows (jdbc/execute! ds
                     ["SELECT COUNT(*) AS cnt FROM positions WHERE account = 'DU999'"]
                     {:builder-fn rs/as-unqualified-lower-maps})]
          (is (= 1 (:cnt (first rows)))))))))

(deftest jdbc-store-read-positions-test
  (testing "JdbcStore.read-positions reads rows from positions table"
    (let [ds    (make-test-ds)
          _     (jdbc/execute! ds
                  ["INSERT INTO positions (account, symbol, opt_right, expiry, strike, quantity, avg_cost)
                    VALUES ('DU999', 'TSLA', '', '1900-01-01', 0.0, 10, 250.0)"])
          store (portfolio/->JdbcStore ds)
          pos   (portfolio/read-positions store "DU999")]
      (is (= 1 (count pos)))
      (is (= "TSLA" (:symbol (first pos))))
      (is (= 10 (:qty (first pos)))))))

(deftest jdbc-store-read-account-summary-test
  (testing "JdbcStore.read-account-summary reads latest row from account_summary table"
    (let [ds    (make-test-ds)
          _     (jdbc/execute! ds
                  ["INSERT INTO account_summary (account, fetched_at, net_liq, cash, buying_power, day_pl)
                    VALUES ('DU999', current_timestamp, 50000.0, 10000.0, 40000.0, 100.0)"])
          store (portfolio/->JdbcStore ds)
          s     (portfolio/read-account-summary store "DU999")]
      (is (= 50000.0 (:net-liq s)))
      (is (= 10000.0 (:cash s))))))

(deftest jdbc-store-write-positions-test
  (testing "JdbcStore.write-positions! persists position data"
    (let [ds    (make-test-ds)
          store (portfolio/->JdbcStore ds)]
      (portfolio/write-positions! store "DU999" [test-position])
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM positions WHERE account = 'DU999'"]
                   {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 1 (count rows)))
        (is (= "TSLA" (:symbol (first rows))))))))

(deftest make-source-mock-test
  (testing "make-source returns MockSource when config says :mock"
    (let [src (portfolio/make-source {:portfolio {:source :mock}} nil nil)]
      (is (satisfies? portfolio/IAccountSource src))
      (is (seq (portfolio/positions src))))))

(deftest make-source-ibkr-test
  (testing "make-source returns IbkrSource when config says :ibkr"
    (let [ds  (make-test-ds)
          src (portfolio/make-source {:portfolio {:source :ibkr :account-id "DU123"}} nil ds)]
      (is (satisfies? portfolio/IAccountSource src)))))

(deftest supported-metrics-test
  (testing "supported-metrics constant is a set of keywords"
    (is (set? portfolio/supported-metrics))
    (is (contains? portfolio/supported-metrics :delta))
    (is (contains? portfolio/supported-metrics :theta))))

(deftest snapshot-version-test
  (testing "snapshot-version is a positive integer"
    (is (pos-int? portfolio/snapshot-version))))
