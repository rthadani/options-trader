(ns options-trader.screener.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [options-trader.screener.registry  :as registry]))

(defn- make-test-ds
  []
  (Class/forName "org.duckdb.DuckDBDriver")
  (let [conn (java.sql.DriverManager/getConnection "jdbc:duckdb:")]
    (jdbc/execute! conn
      ["CREATE TABLE IF NOT EXISTS screens (
          id               VARCHAR   PRIMARY KEY,
          name             VARCHAR   NOT NULL,
          universe         VARCHAR,
          criteria         JSON,
          description_hash VARCHAR,
          cached_sql       TEXT,
          created_at       TIMESTAMP DEFAULT current_timestamp,
          updated_at       TIMESTAMP DEFAULT current_timestamp)"])
    (jdbc/execute! conn
      ["CREATE TABLE IF NOT EXISTS latest_indicators (
          symbol         VARCHAR PRIMARY KEY,
          rsi_14         DOUBLE,
          adx_14         DOUBLE,
          bb_width       DOUBLE,
          chop           DOUBLE,
          stoch_k        DOUBLE,
          stoch_d        DOUBLE,
          macd_hist      DOUBLE,
          williams_r_14  DOUBLE,
          percent_b      DOUBLE)"])
    (jdbc/execute! conn
      ["INSERT INTO latest_indicators
        (symbol, rsi_14, adx_14, bb_width, chop, stoch_k, stoch_d, macd_hist, williams_r_14, percent_b)
        VALUES
        ('AAPL',  28.5, 18.0,  4.2, 65.0, 22.0, 24.0,  0.15, -72.0, 0.12),
        ('MSFT',  62.0, 32.0,  8.1, 42.0, 55.0, 52.0,  0.42, -35.0, 0.65),
        ('NVDA',  71.0, 45.0, 12.0, 30.0, 78.0, 72.0,  0.85, -20.0, 0.82),
        ('AMZN',  33.0, 15.0,  3.8, 70.0, 18.0, 20.0, -0.10, -78.0, 0.08),
        ('GOOGL', 52.0, 25.0,  6.5, 55.0, 45.0, 48.0,  0.20, -48.0, 0.45)"])
    conn))

(deftest run-query-test
  (let [ds (make-test-ds)]
    (testing "returns >= 1 row for valid query"
      (let [{:keys [results error]}
            (registry/run-query ds "SELECT symbol FROM latest_indicators WHERE rsi_14 < 35")]
        (is (nil? error))
        (is (pos? (count results)))))

    (testing "ORDER BY and LIMIT respected"
      (let [{:keys [results]}
            (registry/run-query ds
              "SELECT symbol, rsi_14 FROM latest_indicators WHERE rsi_14 > 0 ORDER BY rsi_14 ASC LIMIT 2")]
        (is (<= (count results) 2))
        (is (apply <= (map :rsi_14 results)))))

    (testing "BETWEEN query"
      (let [{:keys [results error]}
            (registry/run-query ds
              "SELECT symbol FROM latest_indicators WHERE rsi_14 BETWEEN 25 AND 40")]
        (is (nil? error))
        (is (pos? (count results)))))

    (testing "AND conjunction"
      (let [{:keys [results]}
            (registry/run-query ds
              "SELECT symbol FROM latest_indicators WHERE rsi_14 < 70 AND adx_14 < 50")]
        (is (pos? (count results)))))

    (testing "CTE and window function (full DuckDB SQL)"
      (let [{:keys [results error]}
            (registry/run-query ds
              "WITH r AS (SELECT symbol, rsi_14, PERCENT_RANK() OVER (ORDER BY rsi_14) AS pr FROM latest_indicators) SELECT symbol FROM r WHERE pr > 0.5")]
        (is (nil? error))
        (is (pos? (count results)))))

    (testing "syntax error returns :error"
      (let [{:keys [error results]}
            (registry/run-query ds "NOT A QUERY")]
        (is (some? error))
        (is (empty? results))))))

(deftest save-and-run-screen-test
  (let [ds (make-test-ds)]
    (testing "save-screen! upserts and run-screen executes"
      (let [id (registry/save-screen! ds
                 {:name     "test-oversold"
                  :universe "latest_indicators"
                  :dsl      "SELECT symbol FROM latest_indicators WHERE rsi_14 < 35"})]
        (is (string? id))
        (let [{:keys [results error]} (registry/run-screen ds "test-oversold")]
          (is (nil? error))
          (is (pos? (count results))))))

    (testing "list-screens includes saved screen"
      (registry/save-screen! ds
        {:name "test-list-screen"
         :dsl  "SELECT symbol FROM latest_indicators WHERE adx_14 > 20"})
      (let [names (map :name (registry/list-screens ds))]
        (is (some #{"test-list-screen"} names))))

    (testing "get-screen by name"
      (let [s (registry/get-screen ds "test-oversold")]
        (is (= "test-oversold" (:name s)))
        (is (string? (:dsl s)))))

    (testing "delete-screen! removes from DB"
      (registry/save-screen! ds {:name "test-delete" :dsl "SELECT symbol FROM latest_indicators WHERE rsi_14 < 80"})
      (registry/delete-screen! ds "test-delete")
      (is (nil? (registry/get-screen ds "test-delete"))))))

(defn- canned-sh [stdout]
  (fn [& _args] {:exit 0 :out stdout :err ""}))

(deftest description-driven-screen-generates-and-caches-sql
  (let [ds   (make-test-ds)
        opts {:sh-fn (canned-sh "SELECT symbol FROM latest_indicators WHERE rsi_14 < 30")}
        id   (registry/save-screen! ds
               {:name "Oversold NL"
                :description "stocks with RSI under 30"})]
    (is (string? id))
    (testing "first run hits the LLM and caches the SQL"
      (let [r (registry/run-screen ds "Oversold NL" opts)]
        (is (nil? (:error r)))
        (is (some? (:results r)))))
    (testing "cached SQL was persisted"
      (let [s (registry/get-screen ds "Oversold NL")]
        (is (= "SELECT symbol FROM latest_indicators WHERE rsi_14 < 30"
               (:cached-sql s)))
        (is (string? (:description-hash s)))))
    (testing "second run uses cache (LLM would fail if called)"
      (let [bad-opts {:sh-fn (fn [& _] {:exit 1 :out "" :err "should not be called"})}
            r (registry/run-screen ds "Oversold NL" bad-opts)]
        (is (nil? (:error r)))
        (is (some? (:results r)))))))

(deftest description-change-invalidates-cache
  (let [ds (make-test-ds)
        _  (registry/save-screen! ds
             {:name "Changes"
              :description "first description"})
        _  (registry/run-screen ds "Changes"
             {:sh-fn (canned-sh "SELECT symbol FROM latest_indicators LIMIT 1")})]
    (testing "saving with a new description clears the cache"
      (registry/save-screen! ds
        {:name "Changes"
         :description "different description now"})
      (let [s (registry/get-screen ds "Changes")]
        (is (nil? (:cached-sql s)))))))

(deftest missing-indicator-bubbles-up
  (let [ds (make-test-ds)
        _  (registry/save-screen! ds
             {:name "Needs Sortino"
              :description "Stocks with positive sortino"})
        r  (registry/run-screen ds "Needs Sortino"
             {:sh-fn (canned-sh "MISSING sortino")})]
    (is (= "sortino" (:missing r)))
    (is (empty? (:results r)))))

(deftest inline-sql-takes-precedence-over-description
  (let [ds (make-test-ds)
        _  (registry/save-screen! ds
             {:name "Has Both"
              :description "would-be-llm fallback"
              :dsl  "SELECT symbol FROM latest_indicators WHERE rsi_14 < 35"})
        r  (registry/run-screen ds "Has Both"
             {:sh-fn (fn [& _] {:exit 1 :out "" :err "must not be called"})})]
    (is (nil? (:error r)))
    (is (pos? (count (:results r))))))

(deftest description-only-without-cache-or-sql-routes-to-llm
  (let [ds (make-test-ds)
        _  (registry/save-screen! ds
             {:name "Plain NL"
              :description "find oversold names"})
        r  (registry/run-screen ds "Plain NL"
             {:sh-fn (canned-sh "SELECT symbol FROM latest_indicators WHERE rsi_14 < 50")})]
    (is (nil? (:error r)))
    (is (some? (:results r)))))

(deftest screen-with-neither-body-nor-description-errors
  (let [ds (make-test-ds)
        _  (registry/save-screen! ds {:name "Empty"})
        r  (registry/run-screen ds "Empty")]
    (is (re-find #"has neither" (:error r)))))

(deftest run-starter-screen-test
  (let [ds       (make-test-ds)
        f        (io/file "resources/screens/oversold-mean-revert.screen")
        content  (slurp f)
        sql-line (some #(when (str/starts-with? (str/trim %) "SELECT") (str/trim %))
                       (str/split-lines content))]
    (testing "oversold-mean-revert screen returns >= 1 row"
      (let [{:keys [results error]} (registry/run-query ds sql-line)]
        (is (nil? error) (str "Error: " error))
        (is (pos? (count results))
            (str "Expected >= 1 row, got 0 from: " sql-line))))))
