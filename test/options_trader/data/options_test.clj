(ns options-trader.data.options-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.data.options :as opts])
  (:import [options_trader.data.options MockOptionsSource IbkrOptionsSource]))

(deftest make-source-default-returns-unavailable
  (testing "make-source with unknown type returns an UnavailableOptionsSource"
    (let [src (opts/make-source {:type :unknown})]
      (is (= :unavailable (opts/req-chain            src {} "" identity)))
      (is (= :unavailable (opts/req-contract-details src {} identity))))))

(deftest make-source-mock-dispatch
  (testing "make-source :mock returns a MockOptionsSource"
    (let [src (opts/make-source {:type :mock})]
      (is (instance? MockOptionsSource src)))))

(deftest make-source-ibkr-dispatch
  (testing "make-source :ibkr returns an IbkrOptionsSource"
    (let [src (opts/make-source {:type :ibkr :ib-client :stub-conn})]
      (is (instance? IbkrOptionsSource src)))))

(deftest mock-req-chain-returns-req-id
  (testing "MockOptionsSource.req-chain returns a positive integer req-id"
    (let [src (MockOptionsSource.)
          id  (opts/req-chain src {:symbol "AAPL"} "2024-01" identity)]
      (is (pos? id)))))

(deftest mock-req-chain-invokes-callback
  (testing "MockOptionsSource.req-chain invokes callback with a chain map"
    (let [src        (MockOptionsSource.)
          underlying {:symbol "AAPL"}
          result     (atom nil)]
      (opts/req-chain src underlying "2024-01" #(reset! result %))
      (is (some? @result))
      (is (= :mock-chain (:type @result)))
      (is (= underlying (:underlying @result))))))

(deftest mock-req-contract-details-returns-req-id
  (testing "MockOptionsSource.req-contract-details returns a positive integer req-id"
    (let [src (MockOptionsSource.)
          id  (opts/req-contract-details src {:symbol "SPY" :sec-type :STK} identity)]
      (is (pos? id)))))

(deftest mock-req-contract-details-invokes-callback
  (testing "MockOptionsSource.req-contract-details invokes callback with a details map"
    (let [src      (MockOptionsSource.)
          contract {:symbol "SPY" :sec-type :STK}
          result   (atom nil)]
      (opts/req-contract-details src contract #(reset! result %))
      (is (some? @result))
      (is (= :mock-contract-details (:type @result)))
      (is (= contract (:contract @result))))))

(deftest mock-req-chain-forwards-expiry
  (testing "MockOptionsSource.req-chain forwards expiry to the callback"
    (let [src    (MockOptionsSource.)
          result (atom nil)]
      (opts/req-chain src {:symbol "TSLA"} "2025-06" #(reset! result %))
      (is (= "2025-06" (:expiry @result))))))
