(ns options-trader.screener.nl-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [options-trader.screener.nl :as nl]
            [options-trader.test-util :as tu]))

(defn- make-test-ds []
  (Class/forName "org.duckdb.DuckDBDriver")
  (let [conn (java.sql.DriverManager/getConnection "jdbc:duckdb:")]
    (jdbc/execute! conn
      ["CREATE TABLE latest_indicators (
          symbol    VARCHAR PRIMARY KEY,
          rsi_14    DOUBLE,
          macd_hist DOUBLE,
          adx_14    DOUBLE,
          bb_lower  DOUBLE,
          pivot_r1  DOUBLE)"])
    conn))

(deftest schema-summary-includes-columns-with-meanings
  (let [ds (make-test-ds)
        s  (nl/schema-summary ds)]
    (testing "schema lists every column"
      (is (re-find #"\brsi_14\b" s))
      (is (re-find #"\bmacd_hist\b" s))
      (is (re-find #"\bbb_lower\b" s))
      (is (re-find #"\bpivot_r1\b" s)))
    (testing "indicator kind appears alongside the column"
      (is (re-find #"rsi_14.*RSI" s))
      (is (re-find #"bb_lower.*BBLower" s)))))

(deftest description->sql-returns-sql-from-llm
  (let [ds  (make-test-ds)
        res (nl/description->sql ds
              "Oversold names with RSI below 30"
              {:sh-fn (tu/canned-sh "SELECT symbol FROM latest_indicators WHERE rsi_14 < 30 ORDER BY rsi_14 ASC LIMIT 25")})]
    (is (= "SELECT symbol FROM latest_indicators WHERE rsi_14 < 30 ORDER BY rsi_14 ASC LIMIT 25"
           (:sql res)))
    (is (nil? (:error res)))
    (is (nil? (:missing res)))))

(deftest description->sql-strips-markdown-fences
  (let [ds  (make-test-ds)
        res (nl/description->sql ds
              "Anything"
              {:sh-fn (tu/canned-sh "```sql\nSELECT symbol FROM latest_indicators LIMIT 5\n```")})]
    (is (= "SELECT symbol FROM latest_indicators LIMIT 5" (:sql res)))))

(deftest description->sql-detects-missing-column
  (let [ds  (make-test-ds)
        res (nl/description->sql ds
              "Stocks with positive sortino ratio"
              {:sh-fn (tu/canned-sh "MISSING sortino")})]
    (is (= "sortino" (:missing res)))
    (is (nil? (:sql res)))))

(deftest description->sql-empty-response-is-error
  (let [ds  (make-test-ds)
        res (nl/description->sql ds "anything"
              {:sh-fn (tu/canned-sh "")})]
    (is (some? (:error res)))
    (is (nil? (:sql res)))))

(deftest description->sql-llm-failure-surfaces-as-error
  (let [ds  (make-test-ds)
        res (nl/description->sql ds "anything"
              {:sh-fn (fn [& _] {:exit 1 :out "" :err "boom"})})]
    (is (some? (:error res)))
    (is (nil? (:sql res)))))
