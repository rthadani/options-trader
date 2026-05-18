(ns options-trader.data.ibkr.request-id-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [options-trader.data.ibkr.request-id :as req-id]))

(use-fixtures :each
  (fn [f]
    (req-id/reset-state!)
    (f)
    (req-id/reset-state!)))

(deftest next-id-is-monotonic
  (testing "next-id! returns strictly increasing positive integers"
    (let [a (req-id/next-id!)
          b (req-id/next-id!)
          c (req-id/next-id!)]
      (is (pos? a))
      (is (< a b c)))))

(deftest register-and-resolve-callback
  (testing "register! stores a callback that resolve retrieves"
    (let [received (atom nil)
          cb       (fn [msg] (reset! received msg))
          id       (req-id/next-id!)]
      (req-id/register! id cb)
      (let [found (req-id/resolve id)]
        (is (= cb found))
        (found :ping)
        (is (= :ping @received))))))

(deftest complete-removes-registration
  (testing "complete! removes the callback; resolve returns nil afterward"
    (let [id (req-id/next-id!)]
      (req-id/register! id identity)
      (is (some? (req-id/resolve id)))
      (req-id/complete! id)
      (is (nil? (req-id/resolve id))))))

(deftest double-register-overwrites-callback
  (testing "registering a second callback for the same id replaces the first"
    (let [id  (req-id/next-id!)
          cb1 (fn [_] :first)
          cb2 (fn [_] :second)]
      (req-id/register! id cb1)
      (req-id/register! id cb2)
      (is (= cb2 (req-id/resolve id))))))

(deftest complete-unknown-id-is-safe
  (testing "complete! on an unregistered id does not throw"
    (is (nil? (req-id/complete! 99999)))))

(deftest response-handler-dispatches-error
  (testing "response-handler routes :error messages correctly"
    (let [result (req-id/response-handler {:type :error :req-id 7 :code 321})]
      (is (= :error (:handled result)))
      (is (= 7 (:req-id result)))
      (is (= 321 (:code result))))))

(deftest response-handler-dispatches-next-valid-id
  (testing "response-handler routes :next-valid-id messages correctly"
    (let [result (req-id/response-handler {:type :next-valid-id :id 42})]
      (is (= :next-valid-id (:handled result)))
      (is (= 42 (:id result))))))

(deftest response-handler-default-for-unknown-type
  (testing "response-handler :default handles unrecognised message types"
    (let [result (req-id/response-handler {:type :mystery-event :foo :bar})]
      (is (= :unknown (:handled result)))
      (is (= :mystery-event (:type result))))))

(deftest pending-ids-reflects-registry-state
  (testing "pending-ids returns registered ids and shrinks after complete!"
    (let [id1 (req-id/next-id!)
          id2 (req-id/next-id!)]
      (req-id/register! id1 identity)
      (req-id/register! id2 identity)
      (is (contains? (req-id/pending-ids) id1))
      (is (contains? (req-id/pending-ids) id2))
      (req-id/complete! id1)
      (is (not (contains? (req-id/pending-ids) id1)))
      (is (contains? (req-id/pending-ids) id2)))))
