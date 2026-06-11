(ns options-trader.actions.orders-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.actions.core :as actions]
            [options-trader.actions.orders]
            [options-trader.data.orders :as orders]))

(defn- run
  "Build a fresh mock source, dispatch the action with that source attached,
   and return {:result :calls} for assertion."
  [action]
  (let [src    (orders/mock-source)
        result (actions/handle-action (assoc action :order-source src))]
    {:result result :calls (orders/mock-calls src)}))

(def ^:private valid-base
  {:type       :place-order
   :side       "BUY"
   :symbol     "AAPL"
   :quantity   100
   :order-type "MKT"})

(deftest preview-on-first-call
  (testing "no :confirm? returns preview; source untouched"
    (let [{:keys [result calls]} (run valid-base)]
      (is (false? (:confirm? result)))
      (is (map? (:preview result)))
      (is (= "BUY" (-> result :preview :spec :side)))
      (is (empty? calls)))))

(deftest invalid-side-rejected
  (let [{:keys [result calls]} (run (assoc valid-base :side "HOLD"
                                                     :confirm? true))]
    (is (= :invalid-order (:error result)))
    (is (some #{:invalid-side} (:reasons result)))
    (is (empty? calls))))

(deftest invalid-quantity-rejected
  (let [r (:result (run (assoc valid-base :quantity 0 :confirm? true)))]
    (is (= :invalid-order (:error r)))
    (is (some #{:invalid-quantity} (:reasons r)))))

(deftest lmt-without-price-rejected
  (let [r (:result (run (assoc valid-base :order-type "LMT" :confirm? true)))]
    (is (= :invalid-order (:error r)))
    (is (some #{:limit-price-required} (:reasons r)))))

(deftest confirm-without-allow-orders-blocked
  (let [{:keys [result calls]} (run (assoc valid-base :confirm? true
                                                     :allow-orders? false))]
    (is (= :orders-disabled (:error result)))
    (is (empty? calls))))

(deftest confirm-with-no-source-reports-unavailable
  (testing "with no :order-source and no :ib-client → :send-failed"
    (let [r (actions/handle-action
              (assoc valid-base :confirm? true :allow-orders? true))]
      (is (= :send-failed (:error r))))))

(deftest confirm-with-all-guards-sends
  (let [{:keys [result calls]} (run (assoc valid-base :confirm? true
                                                     :allow-orders? true))]
    (is (true? (:ok result)))
    (is (number? (:order-id result)))
    (is (= 1 (count calls)))
    (let [call (first calls)]
      (is (= :place (:type call)))
      (is (= "AAPL" (-> call :spec :symbol)))
      (is (= "BUY"  (-> call :spec :side)))
      (is (= "MKT"  (-> call :spec :order-type))))))

(deftest lmt-confirm-sends-with-price
  (let [{:keys [result calls]} (run (assoc valid-base
                                           :order-type "LMT"
                                           :limit-price 150.0
                                           :confirm? true
                                           :allow-orders? true))]
    (is (true? (:ok result)))
    (is (= 150.0 (-> calls first :spec :limit-price)))))

(deftest bag-without-combo-legs-rejected
  (let [r (:result (run (assoc valid-base :sec-type "BAG" :confirm? true)))]
    (is (= :invalid-order (:error r)))
    (is (some #{:combo-legs-required} (:reasons r)))))

(deftest bag-confirm-sends-with-legs
  (let [legs [{:conid 1 :ratio 1 :action "BUY"  :exchange "SMART"}
              {:conid 2 :ratio 1 :action "SELL" :exchange "SMART"}]
        {:keys [result calls]} (run (-> valid-base
                                        (assoc :sec-type "BAG"
                                               :combo-legs legs
                                               :order-type "LMT"
                                               :limit-price 1.50
                                               :quantity 1
                                               :confirm? true
                                               :allow-orders? true)))]
    (is (true? (:ok result)))
    (is (= "BAG" (-> calls first :spec :sec-type)))
    (is (= 2 (count (-> calls first :spec :combo-legs))))))

(deftest cancel-preview-on-first-call
  (let [{:keys [result calls]} (run {:type :cancel-order :order-id 99})]
    (is (false? (:confirm? result)))
    (is (= 99 (-> result :preview :order-id)))
    (is (empty? calls))))

(deftest cancel-confirm-blocked-without-allow-orders
  (let [r (:result (run {:type :cancel-order :order-id 99
                         :confirm? true :allow-orders? false}))]
    (is (= :orders-disabled (:error r)))))

(deftest cancel-confirm-sends
  (let [{:keys [result calls]} (run {:type :cancel-order :order-id 99
                                     :confirm? true :allow-orders? true})]
    (is (true? (:ok result)))
    (is (= :cancel (-> calls first :type)))
    (is (= 99 (-> calls first :order-id)))))

(deftest cancel-rejects-missing-id
  (let [r (:result (run {:type :cancel-order :confirm? true
                         :allow-orders? true}))]
    (is (= :missing-order-id (:error r)))))

(deftest unavailable-source-surfaces-error
  (let [src (orders/make-source {})  ; UnavailableOrderSource
        r   (actions/handle-action
              (assoc valid-base :confirm? true :allow-orders? true
                                :order-source src))]
    (is (= :send-failed (:error r)))))
