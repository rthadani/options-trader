(ns options-trader.data.ibkr.pacer-test
  "Each test creates a fresh pacer instance via create-pacer so there is
   no shared mutable state between tests."
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.data.ibkr.pacer :as pacer]))

(deftest acquire-consumes-one-token
  (testing "acquire! returns true and decrements the token count by one"
    (let [p      (pacer/create-pacer)
          _      (pacer/set-tokens! p 10)
          before (:tokens (pacer/stats p))]
      (is (true? (pacer/acquire! p)))
      (is (= (dec before) (:tokens (pacer/stats p)))))))

(deftest acquire-fails-when-bucket-empty
  (testing "acquire! returns false when zero tokens remain"
    (let [p (pacer/create-pacer)]
      (pacer/set-tokens! p 0)
      (is (false? (pacer/acquire! p))))))

(deftest with-budget-executes-body-with-tokens
  (testing "with-budget returns {:status :ok :result v} when tokens are available"
    (let [p      (pacer/create-pacer {:burst 20})
          result (pacer/with-budget p (* 6 7))]
      (is (= :ok (:status result)))
      (is (= 42  (:result result))))))

(deftest with-budget-queues-when-exhausted
  (testing "with-budget returns {:status :queued} and does NOT execute body when empty"
    (let [p           (pacer/create-pacer)
          _           (pacer/set-tokens! p 0)
          side-effect (atom false)
          result      (pacer/with-budget p (reset! side-effect true))]
      (is (= :queued (:status result)))
      (is (false? @side-effect) "body must not execute when queued"))))

(deftest queue-depth-grows-under-budget-pressure
  (testing "consecutive with-budget calls under zero tokens grow queue-depth"
    (let [p (pacer/create-pacer)]
      (pacer/set-tokens! p 0)
      (pacer/with-budget p :a)
      (pacer/with-budget p :b)
      (pacer/with-budget p :c)
      (is (= 3 (:queue-depth (pacer/stats p)))))))

(deftest stats-reflects-set-tokens
  (testing "stats returns the exact token count after set-tokens!"
    (let [p (pacer/create-pacer)]
      (pacer/set-tokens! p 37)
      (let [{:keys [tokens queue-depth]} (pacer/stats p)]
        (is (= 37 tokens))
        (is (= 0  queue-depth))))))

(deftest prune-window-removes-old-timestamps
  (testing "prune-window filters entries older than 10 minutes"
    (let [now    (System/currentTimeMillis)
          old    (- now (* 11 60 1000))
          recent (- now (*  5 60 1000))
          result (pacer/prune-window [old recent] now)]
      (is (= [recent] result)))))

(deftest prune-window-keeps-all-recent
  (testing "prune-window keeps all timestamps within the 10-min window"
    (let [now (System/currentTimeMillis)
          ts  [(- now 1000) (- now 5000) (- now 30000)]]
      (is (= ts (pacer/prune-window ts now))))))

(deftest hist-request-count-increments
  (testing "hist-request-count grows after each record-hist-request!"
    (let [p (pacer/create-pacer)]
      (is (= 0 (pacer/hist-request-count p "AAPL" "1 min")))
      (pacer/record-hist-request! p "AAPL" "1 min")
      (pacer/record-hist-request! p "AAPL" "1 min")
      (is (= 2 (pacer/hist-request-count p "AAPL" "1 min"))))))

(deftest hist-windows-are-independent-per-key
  (testing "separate [symbol bar-size] keys maintain independent counts"
    (let [p (pacer/create-pacer)]
      (pacer/record-hist-request! p "AAPL" "1 min")
      (pacer/record-hist-request! p "MSFT" "5 mins")
      (pacer/record-hist-request! p "MSFT" "5 mins")
      (is (= 1 (pacer/hist-request-count p "AAPL" "1 min")))
      (is (= 2 (pacer/hist-request-count p "MSFT" "5 mins"))))))

(deftest can-request-historical-respects-cap
  (testing "can-request-historical? returns false once hist-window-max is reached"
    (let [p (pacer/create-pacer)]
      (dotimes [_ pacer/hist-window-max]
        (pacer/record-hist-request! p "TSLA" "30 mins"))
      (is (false? (pacer/can-request-historical? p "TSLA" "30 mins"))))))

(deftest burst-allows-n-immediate-calls
  (testing "create-pacer with burst 5 allows exactly 5 immediate acquire! calls"
    (let [p (pacer/create-pacer {:rate 5 :burst 5})]
      (dotimes [_ 5]
        (is (true? (pacer/acquire! p)) "each of the 5 burst calls must succeed"))
      (is (false? (pacer/acquire! p)) "6th call must fail — burst exhausted"))))

(deftest burst-queues-sixth-call
  (testing "with-budget on a burst-5 pacer queues the 6th call"
    (let [p (pacer/create-pacer {:rate 5 :burst 5})]
      (dotimes [_ 5]
        (pacer/with-budget p :work))
      (let [r (pacer/with-budget p :overflow)]
        (is (= :queued (:status r)))
        (is (= 1 (:queue-depth r)))))))
