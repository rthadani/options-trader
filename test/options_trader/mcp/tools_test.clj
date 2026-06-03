(ns options-trader.mcp.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [options-trader.db.duckdb :as db]
            [options-trader.mcp.tools :as tools])
  (:import [java.io File]))

(defn- mem-ds []
  (db/datasource {:db {:path ":memory:"}}))

(defn- bootstrapped-ds []
  (let [f    (File/createTempFile "tools-test-" ".duckdb")
        path (.getAbsolutePath f)]
    (.delete f)
    (db/bootstrap! {:db {:path path}})
    (db/datasource {:db {:path path}})))

(deftest load-registry-test
  (testing "loads every tool schema from resources"
    (let [reg (tools/load-registry)]
      (is (map? reg))
      (is (= 19 (count reg)))
      (doseq [nm ["portfolio_summary" "list_screens" "run_screen" "run_sql"
                  "get_indicators" "place_order" "cancel_order"
                  "fetch_news" "fetch_filings" "fetch_filing_body"
                  "fetch_filing_item" "fetch_xbrl_facts"
                  "fetch_corporate_actions" "fetch_fundamentals"
                  "fetch_earnings_history" "fetch_short_interest"
                  "fetch_option_chain" "fetch_option_quote"
                  "fetch_detailed_quote"]]
        (is (contains? reg nm) (str "missing schema: " nm)))))

  (testing "each schema has name, description, inputSchema"
    (let [reg (tools/load-registry)]
      (doseq [[_ schema] reg]
        (is (string? (:name schema)))
        (is (string? (:description schema)))
        (is (map? (:inputSchema schema)))))))

(deftest list-tools-test
  (testing "returns vector of tool descriptors"
    (let [reg   (tools/load-registry)
          items (tools/list-tools reg)]
      (is (vector? items))
      (is (= 19 (count items)))
      (doseq [t items]
        (is (string? (:name t)))
        (is (string? (:description t)))
        (is (map? (:inputSchema t)))))))

(deftest call-tool-portfolio-summary-test
  (testing "returns positions and account-summary"
    (let [result (tools/call-tool "portfolio_summary" {} {})]
      (is (map? result))
      (is (contains? result :positions))
      (is (contains? result :account-summary)))))

(deftest call-tool-list-screens-test
  (testing "returns screens key even without ds"
    (let [result (tools/call-tool "list_screens" {} {})]
      (is (contains? result :screens))
      (is (vector? (:screens result))))))

(deftest call-tool-run-screen-without-ds-test
  (testing "returns error when no ds"
    (let [result (tools/call-tool "run_screen" {:screen_id "test"} {})]
      (is (contains? result :error)))))

(deftest call-tool-get-indicators-without-ds-test
  (testing "returns error when no ds"
    (let [result (tools/call-tool "get_indicators" {:symbols ["AAPL"]} {})]
      (is (contains? result :error)))))

(deftest call-tool-get-indicators-empty-symbols-test
  (testing "returns error for empty symbols list"
    (let [ds     (mem-ds)
          result (tools/call-tool "get_indicators" {:symbols []} {:ds ds})]
      (is (contains? result :error)))))

(deftest call-tool-get-indicators-with-ds-test
  (testing "returns indicators map with real db"
    (let [ds     (bootstrapped-ds)
          result (tools/call-tool "get_indicators" {:symbols ["AAPL" "MSFT"]} {:ds ds})]
      (is (map? result))
      (is (contains? result :symbols))
      (is (contains? result :indicators))
      (is (vector? (:indicators result)))
      (is (= ["AAPL" "MSFT"] (:symbols result))))))

(deftest call-tool-place-order-disabled-test
  (testing "returns orders_disabled by default"
    (let [result (tools/call-tool "place_order"
                   {:symbol "AAPL" :action "BUY" :quantity 1 :order_type "MKT"}
                   {})]
      (is (= "orders_disabled" (:error result))))))

(deftest call-tool-cancel-order-disabled-test
  (testing "returns orders_disabled by default"
    (let [result (tools/call-tool "cancel_order" {:order_id "123"} {})]
      (is (= "orders_disabled" (:error result))))))

(deftest call-tool-unknown-test
  (testing "unknown tool returns error"
    (let [result (tools/call-tool "nonexistent_tool" {} {})]
      (is (contains? result :error)))))
