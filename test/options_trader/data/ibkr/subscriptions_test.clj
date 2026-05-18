(ns options-trader.data.ibkr.subscriptions-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.data.ibkr.subscriptions :as subs]))

(deftest subscribe-adds-to-active-subs
  (testing "subscribe! makes the conid appear in active-subs"
    (let [mgr (subs/create-manager)]
      (subs/subscribe! mgr 12345 :market-data)
      (is (contains? (subs/active-subs mgr) 12345)))))

(deftest subscribe-returns-ok-map
  (testing "subscribe! returns {:ok true :conid c :kind k} on success"
    (let [mgr    (subs/create-manager)
          result (subs/subscribe! mgr 42 :quotes)]
      (is (true? (:ok result)))
      (is (= 42 (:conid result)))
      (is (= :quotes (:kind result))))))

(deftest active-subs-records-kind
  (testing "active-subs stores the :kind for each subscription"
    (let [mgr (subs/create-manager)]
      (subs/subscribe! mgr 777 :options)
      (is (= :options (get-in (subs/active-subs mgr) [777 :kind]))))))

(deftest unsubscribe-removes-from-active
  (testing "unsubscribe! causes conid to disappear from active-subs"
    (let [mgr (subs/create-manager)]
      (subs/subscribe! mgr 99 :ticks)
      (subs/unsubscribe! mgr 99)
      (is (not (contains? (subs/active-subs mgr) 99))))))

(deftest unsubscribe-unknown-returns-error
  (testing "unsubscribe! on an unknown conid returns {:error :not-subscribed}"
    (let [mgr    (subs/create-manager)
          result (subs/unsubscribe! mgr 9999)]
      (is (= :not-subscribed (:error result)))
      (is (= 9999 (:conid result))))))

(deftest subscribe-at-cap-returns-cap-error
  (testing "subscribe! returns {:error :subscription-cap-exceeded} when cap is full"
    (let [mgr (subs/create-manager 2)]
      (subs/subscribe! mgr 1 :a)
      (subs/subscribe! mgr 2 :b)
      (let [result (subs/subscribe! mgr 3 :c)]
        (is (= :subscription-cap-exceeded (:error result)))))))

(deftest unsubscribe-frees-slot-for-new-subscription
  (testing "after unsubscribing, a new subscription can succeed within cap"
    (let [mgr (subs/create-manager 1)]
      (subs/subscribe! mgr 1 :a)
      (subs/unsubscribe! mgr 1)
      (let [result (subs/subscribe! mgr 2 :b)]
        (is (true? (:ok result)))
        (is (= 2 (:conid result)))))))

(deftest multiple-managers-are-independent
  (testing "two managers created separately do not share subscription state"
    (let [mgr1 (subs/create-manager 1)
          mgr2 (subs/create-manager 1)]
      (subs/subscribe! mgr1 1 :x)
      (subs/subscribe! mgr2 1 :y)
      (is (= 1 (count (subs/active-subs mgr1))))
      (is (= 1 (count (subs/active-subs mgr2))))
      (is (= :subscription-cap-exceeded
             (:error (subs/subscribe! mgr1 2 :z))))
      (is (= :subscription-cap-exceeded
             (:error (subs/subscribe! mgr2 2 :z)))))))
