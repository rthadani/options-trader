(ns options-trader.mcp.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.mcp.protocol :as protocol]))

(deftest parse-message-test
  (testing "valid JSON object"
    (let [m (protocol/parse-message "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")]
      (is (map? m))
      (is (= "ping" (:method m)))
      (is (= 1 (:id m)))))

  (testing "invalid JSON returns parse-error map"
    (let [m (protocol/parse-message "{bad json}")]
      (is (:parse-error m))))

  (testing "empty string returns parse-error"
    (let [m (protocol/parse-message "")]
      (is (:parse-error m)))))

(deftest encode-message-test
  (testing "encodes map to JSON string"
    (let [s (protocol/encode-message {:jsonrpc "2.0" :id 1 :result {}})]
      (is (string? s))
      (is (.contains s "jsonrpc")))))

(deftest response-test
  (testing "builds success response"
    (let [r (protocol/response 42 {:ok true})]
      (is (= "2.0" (:jsonrpc r)))
      (is (= 42 (:id r)))
      (is (= {:ok true} (:result r))))))

(deftest error-response-test
  (testing "builds error response"
    (let [r (protocol/error-response 1 protocol/err-method-nf "method not found")]
      (is (= 1 (:id r)))
      (is (= protocol/err-method-nf (get-in r [:error :code]))))))

(deftest dispatch-test
  (testing "dispatches known method and returns result"
    (let [handler (fn [{:keys [method]}]
                    (when (= method "ping") {:pong true}))
          msg {:jsonrpc "2.0" :id 1 :method "ping" :params {}}
          resp (protocol/dispatch msg handler)]
      (is (= {:pong true} (:result resp)))
      (is (= 1 (:id resp)))))

  (testing "notification (no id) returns nil"
    (let [handler (fn [_] {:ok true})
          msg {:jsonrpc "2.0" :method "notify" :params {}}
          resp (protocol/dispatch msg handler)]
      (is (nil? resp))))

  (testing "parse-error triggers error response"
    (let [handler (fn [_] {})
          msg {:parse-error true :message "bad"}
          resp (protocol/dispatch msg handler)]
      (is (= protocol/err-parse (get-in resp [:error :code])))))

  (testing "non-map triggers invalid-request error"
    (let [handler (fn [_] {})
          resp (protocol/dispatch "not-a-map" handler)]
      (is (= protocol/err-invalid-req (get-in resp [:error :code]))))))

(deftest stdio-loop-test
  (testing "processes request and writes response"
    (let [input   (java.io.StringReader. "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":{}}\n")
          rdr     (java.io.BufferedReader. input)
          out     (java.io.StringWriter.)
          wtr     (java.io.PrintWriter. out true)
          handler (fn [_] {:echo "ok"})]
      (protocol/stdio-loop rdr wtr handler)
      (let [resp-str (.toString out)]
        (is (not (empty? resp-str)))
        (is (.contains resp-str "echo"))))))
