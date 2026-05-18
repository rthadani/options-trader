(ns options-trader.mcp.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [options-trader.mcp.server :as server]))

(defn- run-requests
  "Feed newline-delimited JSON-RPC strings through run-stdio-server.
   Returns the response strings as a vector."
  [request-lines]
  (let [input   (java.io.StringReader. (clojure.string/join "\n" (conj (vec request-lines) "")))
        rdr     (java.io.BufferedReader. input)
        out     (java.io.StringWriter.)
        wtr     (java.io.PrintWriter. out true)]
    (server/run-stdio-server rdr wtr {})
    (->> (clojure.string/split-lines (.toString out))
         (filter (complement clojure.string/blank?))
         (mapv #(json/parse-string % true)))))

(deftest initialize-test
  (testing "initialize returns protocolVersion and serverInfo"
    (let [req  (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
          resp (first (run-requests [req]))]
      (is (= 1 (:id resp)))
      (is (= "2024-11-05" (get-in resp [:result :protocolVersion])))
      (is (= "options-trader-mcp" (get-in resp [:result :serverInfo :name]))))))

(deftest tools-list-test
  (testing "tools/list returns every registered tool"
    (let [req  (json/generate-string {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
          resp (first (run-requests [req]))]
      (is (= 2 (:id resp)))
      (is (= 18 (count (get-in resp [:result :tools])))))))

(deftest tools-call-portfolio-summary-test
  (testing "tools/call portfolio_summary returns content"
    (let [req  (json/generate-string {:jsonrpc "2.0" :id 3
                                      :method "tools/call"
                                      :params {:name "portfolio_summary" :arguments {}}})
          resp (first (run-requests [req]))]
      (is (= 3 (:id resp)))
      (is (vector? (get-in resp [:result :content]))))))

(deftest tools-call-unknown-method-test
  (testing "unknown method returns error code"
    (let [req  (json/generate-string {:jsonrpc "2.0" :id 4 :method "bogus/method" :params {}})
          resp (first (run-requests [req]))]
      (is (= 4 (:id resp)))
      (is (or (contains? resp :error)
              (contains? (:result resp) :error))))))

(deftest tools-call-missing-name-test
  (testing "tools/call without name returns isError"
    (let [req  (json/generate-string {:jsonrpc "2.0" :id 5
                                      :method "tools/call"
                                      :params {:arguments {}}})
          resp (first (run-requests [req]))]
      (is (= 5 (:id resp)))
      (is (or (true? (get-in resp [:result :isError]))
              (contains? resp :error))))))

(deftest multiple-requests-test
  (testing "server processes multiple requests in sequence"
    (let [reqs [(json/generate-string {:jsonrpc "2.0" :id 10 :method "initialize" :params {}})
                (json/generate-string {:jsonrpc "2.0" :id 11 :method "tools/list" :params {}})]
          resps (run-requests reqs)]
      (is (= 2 (count resps)))
      (is (= 10 (:id (first resps))))
      (is (= 11 (:id (second resps)))))))
