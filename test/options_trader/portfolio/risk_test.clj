(ns options-trader.portfolio.risk-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.portfolio.risk :as risk]))

(def ^:private long-stock
  {:symbol "AAPL" :opt-right "" :qty 100 :avg-cost 185.50 :strike 0.0 :market-value 19000.0})

(def ^:private short-stock
  {:symbol "AAPL" :opt-right "" :qty -100 :avg-cost 185.50 :strike 0.0 :market-value -19000.0})

(def ^:private long-call
  {:symbol "AAPL" :opt-right "C" :qty 1 :avg-cost 5.0 :strike 190.0 :market-value 250.0})

(def ^:private short-call
  {:symbol "AAPL" :opt-right "C" :qty -1 :avg-cost 5.0 :strike 190.0 :market-value -250.0})

(def ^:private long-put
  {:symbol "AAPL" :opt-right "P" :qty 1 :avg-cost 3.50 :strike 180.0 :market-value 200.0})

(def ^:private short-put
  {:symbol "AAPL" :opt-right "P" :qty -1 :avg-cost 3.50 :strike 180.0 :market-value 250.0})

(deftest max-loss-long-stock-test
  (testing "long stock max-loss = avg-cost * qty (goes to zero)"
    (is (= (* 185.50 100) (risk/max-loss-for long-stock)))))

(deftest max-loss-short-stock-test
  (testing "short stock max-loss is :unlimited"
    (is (= :unlimited (risk/max-loss-for short-stock)))))

(deftest max-loss-long-call-test
  (testing "long call max-loss = premium * qty * 100"
    (is (= (* 5.0 1 100) (risk/max-loss-for long-call)))))

(deftest max-loss-short-call-test
  (testing "short naked call max-loss is :unlimited"
    (is (= :unlimited (risk/max-loss-for short-call)))))

(deftest max-loss-long-put-test
  (testing "long put max-loss = premium paid"
    (is (= (* 3.50 1 100) (risk/max-loss-for long-put)))))

(deftest max-loss-short-put-test
  (testing "short put max-loss = strike * contracts * 100"
    (is (= (* 180.0 1 100) (risk/max-loss-for short-put)))))

(deftest max-loss-nil-opt-right-test
  (testing "nil opt-right treated as stock"
    (let [pos {:opt-right nil :qty 10 :avg-cost 100.0 :strike 0.0}]
      (is (= 1000.0 (risk/max-loss-for pos))))))

(deftest margin-required-short-put-test
  (testing "short put margin = 20% * strike * 100 * contracts"
    (is (= (* 0.20 180.0 100 1) (risk/margin-required short-put)))))

(deftest margin-required-short-call-test
  (testing "short call margin = 20% * strike * 100 * contracts"
    (is (= (* 0.20 190.0 100 1) (risk/margin-required short-call)))))

(deftest margin-required-long-options-test
  (testing "long options require no margin"
    (is (zero? (risk/margin-required long-call)))
    (is (zero? (risk/margin-required long-put)))))

(deftest margin-required-stock-test
  (testing "stock positions require no margin from this helper"
    (is (zero? (risk/margin-required long-stock)))
    (is (zero? (risk/margin-required short-stock)))))

(deftest position-pct-net-liq-basic-test
  (testing "returns (market-value / net-liq) * 100"
    (let [pos {:market-value 10000.0}]
      (is (= 10.0 (risk/position-pct-net-liq pos 100000.0))))))

(deftest position-pct-net-liq-small-pct-test
  (testing "returns correct pct for small position"
    (let [pos {:market-value 500.0}]
      (is (< (Math/abs (- 0.5 (risk/position-pct-net-liq pos 100000.0))) 1e-9)))))

(deftest position-pct-net-liq-zero-net-liq-test
  (testing "returns nil when net-liq is zero"
    (is (nil? (risk/position-pct-net-liq {:market-value 100.0} 0.0)))))

(deftest position-pct-net-liq-nil-net-liq-test
  (testing "returns nil when net-liq is nil"
    (is (nil? (risk/position-pct-net-liq {:market-value 100.0} nil)))))

(deftest position-pct-net-liq-negative-test
  (testing "returns negative pct for short position with negative market-value"
    (let [pos {:market-value -2500.0}]
      (is (neg? (risk/position-pct-net-liq pos 100000.0))))))
