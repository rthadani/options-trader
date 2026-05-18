(ns options-trader.data.market-data-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.data.market-data :as md])
  (:import [options_trader.data.market_data MockMarketDataSource IbkrMarketDataSource]))

(deftest make-source-default-returns-unavailable
  (testing "make-source with unknown type returns an UnavailableMarketDataSource"
    (let [src (md/make-source {:type :unknown})]
      (is (= :unavailable (md/get-quote        src {} "" identity)))
      (is (= :unavailable (md/subscribe-quotes src {} "" identity)))
      (is (= :unavailable (md/snapshot         src {} "" identity))))))

(deftest make-source-mock-dispatch
  (testing "make-source :mock returns a MockMarketDataSource"
    (let [src (md/make-source {:type :mock})]
      (is (instance? MockMarketDataSource src)))))

(deftest make-source-ibkr-dispatch
  (testing "make-source :ibkr returns an IbkrMarketDataSource"
    (let [src (md/make-source {:type :ibkr :ib-client :stub-conn})]
      (is (instance? IbkrMarketDataSource src)))))

(deftest mock-get-quote-returns-req-id
  (testing "MockMarketDataSource.get-quote returns a positive integer req-id"
    (let [src (MockMarketDataSource.)
          id  (md/get-quote src {:symbol "AAPL"} "" identity)]
      (is (pos? id)))))

(deftest mock-get-quote-invokes-callback
  (testing "MockMarketDataSource.get-quote invokes callback with a result map"
    (let [src    (MockMarketDataSource.)
          result (atom nil)]
      (md/get-quote src {:symbol "AAPL"} "" #(reset! result %))
      (is (some? @result))
      (is (= :mock-quote (:type @result))))))

(deftest mock-subscribe-quotes-returns-req-id
  (testing "MockMarketDataSource.subscribe-quotes returns a positive integer req-id"
    (let [src (MockMarketDataSource.)
          id  (md/subscribe-quotes src {:symbol "MSFT"} "" identity)]
      (is (pos? id)))))

(deftest mock-subscribe-quotes-invokes-callback
  (testing "MockMarketDataSource.subscribe-quotes invokes callback with a tick map"
    (let [src    (MockMarketDataSource.)
          result (atom nil)]
      (md/subscribe-quotes src {:symbol "MSFT"} "" #(reset! result %))
      (is (= :mock-tick (:type @result))))))

(deftest mock-snapshot-returns-req-id
  (testing "MockMarketDataSource.snapshot returns a positive integer req-id"
    (let [src (MockMarketDataSource.)
          id  (md/snapshot src {:symbol "SPY"} "" identity)]
      (is (pos? id)))))

(deftest mock-snapshot-invokes-callback
  (testing "MockMarketDataSource.snapshot invokes callback with a snapshot map"
    (let [src    (MockMarketDataSource.)
          result (atom nil)]
      (md/snapshot src {:symbol "SPY"} "" #(reset! result %))
      (is (= :mock-snapshot (:type @result))))))

(deftest mock-forwards-contract
  (testing "Mock impls forward the contract to the callback"
    (let [src      (MockMarketDataSource.)
          contract {:symbol "GOOG" :conid 12345}
          result   (atom nil)]
      (md/get-quote src contract "233" #(reset! result %))
      (is (= contract (:contract @result))))))
