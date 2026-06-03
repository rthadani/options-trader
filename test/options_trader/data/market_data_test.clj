(ns options-trader.data.market-data-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.data.market-data :as md])
  (:import [options_trader.data.market_data
            IbkrMarketDataSource MockMarketDataSource UnavailableMarketDataSource]))

(deftest make-source-default-returns-unavailable
  (testing "make-source with unknown type returns an UnavailableMarketDataSource"
    (let [src (md/make-source {:type :unknown})]
      (is (instance? UnavailableMarketDataSource src))
      (is (= :unavailable (md/snapshot-stk src "AAPL")))
      (is (= :unavailable (md/snapshot-opt src {:symbol "AAPL" :expiry "20260918" :strike 200 :right :call})))
      (is (= :unavailable (md/stream-opt   src {:symbol "AAPL" :expiry "20260918" :strike 200 :right :call} 100)))
      (is (= :unavailable (md/calc-iv      src {:symbol "AAPL" :expiry "20260918" :strike 200 :right :call
                                                :option-price 3.5 :underlying-price 200.0}))))))

(deftest make-source-mock-dispatch
  (testing "make-source :mock returns a MockMarketDataSource"
    (let [src (md/make-source {:type :mock})]
      (is (instance? MockMarketDataSource src)))))

(deftest make-source-ibkr-dispatch
  (testing "make-source :ibkr returns an IbkrMarketDataSource"
    (let [src (md/make-source {:type :ibkr :ib-client :stub-conn})]
      (is (instance? IbkrMarketDataSource src)))))

(deftest ibkr-source-without-client-is-unavailable
  (testing "IbkrMarketDataSource with nil ib-client returns :unavailable everywhere"
    (let [src (md/make-source {:type :ibkr :ib-client nil})]
      (is (= :unavailable (md/snapshot-stk src "AAPL")))
      (is (= :unavailable (md/snapshot-opt src {:symbol "AAPL" :expiry "20260918" :strike 200 :right :call})))
      (is (= :unavailable (md/calc-iv      src {:symbol "AAPL" :expiry "20260918" :strike 200 :right :call
                                                :option-price 3.5 :underlying-price 200.0}))))))

(deftest mock-returns-canned-snapshot
  (testing "MockMarketDataSource returns the canned snapshot-stk response"
    (let [src (md/make-mock-source
                {:snapshot-stk {"AAPL" {:bid 200.10 :ask 200.15 :last 200.12}}})]
      (is (= {:bid 200.10 :ask 200.15 :last 200.12}
             (md/snapshot-stk src "AAPL")))
      (is (= :unavailable (md/snapshot-stk src "MSFT"))))))

(deftest mock-returns-canned-opt-snapshot
  (testing "MockMarketDataSource snapshot-opt keys off :symbol"
    (let [src (md/make-mock-source
                {:snapshot-opt {"MPWR" {:bid 1.20 :ask 1.50 :close 1.35}}})]
      (is (= {:bid 1.20 :ask 1.50 :close 1.35}
             (md/snapshot-opt src {:symbol "MPWR" :expiry "20260918"
                                   :strike 1500 :right :put}))))))

(deftest mock-returns-canned-stream-and-calc
  (testing "MockMarketDataSource stream-opt and calc-iv return their canned values"
    (let [src (md/make-mock-source
                {:stream-opt {"MPWR" {:iv 0.41 :delta -0.91}}
                 :calc-iv    {"MPWR" {:iv 0.41 :delta -0.91 :gamma 0.0001}}})]
      (is (= {:iv 0.41 :delta -0.91}
             (md/stream-opt src {:symbol "MPWR" :expiry "20260918"
                                 :strike 1500 :right :put} 100)))
      (is (= {:iv 0.41 :delta -0.91 :gamma 0.0001}
             (md/calc-iv src {:symbol "MPWR" :expiry "20260918"
                              :strike 1500 :right :put
                              :option-price 1.35 :underlying-price 1450.0}))))))
