(ns options-trader.tui.state-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [options-trader.tui.state :as st]))

(use-fixtures :each (fn [f] (st/reset-state!) (f) (st/reset-state!)))


(deftest upsert-position-matches-by-conid-when-present
  (st/set-portfolio! {:positions [{:conid 1 :symbol "AAPL" :qty 100 :market-value 17500}]
                      :account-summary nil :account-id "X"})
  (st/upsert-position! {:conid 1 :symbol "AAPL" :qty 100 :market-value 18000})
  (let [v (:positions @st/state)]
    (is (= 1 (count v)))
    (is (= 18000 (-> v first :market-value)))))

(deftest upsert-position-falls-back-to-natural-key-when-conid-missing-on-existing
  ;; This is the duplicate bug regression: DB-loaded rows have no conid, but
  ;; stream events do. Without natural-key fallback, the stream would append
  ;; instead of replace and we'd see two rows for the same position.
  (st/set-portfolio! {:positions [{:symbol "AAPL" :opt-right "" :strike 0.0
                                   :expiry nil :qty 100 :market-value 17500}]
                      :account-summary nil :account-id "X"})
  (st/upsert-position! {:conid 265598 :symbol "AAPL" :opt-right "" :strike 0.0
                        :expiry nil :qty 100 :market-value 18250})
  (let [v (:positions @st/state)]
    (is (= 1 (count v)) "stream event replaced the DB-loaded row, no duplicate")
    (is (= 18250 (-> v first :market-value)))
    (is (= 265598 (-> v first :conid)))))

(deftest upsert-position-drops-row-when-qty-zero
  (st/set-portfolio! {:positions [{:conid 1 :symbol "AAPL" :qty 100}]
                      :account-summary nil :account-id "X"})
  (st/upsert-position! {:conid 1 :symbol "AAPL" :qty 0})
  (is (empty? (:positions @st/state))))

(deftest upsert-position-ignores-new-zero-qty-row
  (st/set-portfolio! {:positions [] :account-summary nil :account-id "X"})
  (st/upsert-position! {:conid 1 :symbol "AAPL" :qty 0})
  (is (empty? (:positions @st/state))))

(deftest upsert-position-appends-new-symbol
  (st/set-portfolio! {:positions [{:conid 1 :symbol "AAPL" :qty 100}]
                      :account-summary nil :account-id "X"})
  (st/upsert-position! {:conid 2 :symbol "MSFT" :qty 50})
  (is (= 2 (count (:positions @st/state)))))

(deftest user-and-assistant-go-to-conversation
  (st/append-message! :user "USERMSG")
  (st/append-message! :assistant "REPLY")
  (is (= ["USERMSG" "REPLY"] (mapv :text (:messages @st/state))))
  (is (empty? (:activity @st/state))))

(deftest system-messages-route-to-activity-not-conversation
  (st/append-message! :system "SYSNOTE")
  (is (some #(= "SYSNOTE" (:text %)) (:activity @st/state)))
  (is (not (some #(= "SYSNOTE" (:text %)) (:messages @st/state)))))

(deftest append-activity-records-role-and-text
  (st/append-activity! :tool "run_sql")
  (st/append-activity! :thinking "pondering")
  (is (= [{:role :tool :text "run_sql"} {:role :thinking :text "pondering"}]
         (:activity @st/state))))

(deftest take-prefix-returns-nil-when-empty
  (is (nil? (st/take-prefix-message!))))

(deftest queue-then-take-returns-the-string
  (st/queue-prefix-message! "prior session: investigated AAPL fundamentals")
  (is (= "prior session: investigated AAPL fundamentals"
         (st/take-prefix-message!))))

(deftest take-clears-the-queue-after-reading
  (st/queue-prefix-message! "one")
  (st/take-prefix-message!)
  (is (nil? (st/take-prefix-message!))
      "second take returns nil because the first consumed the queue")
  (is (nil? (:prefix-message @st/state))
      "the underlying state value is also cleared"))

(deftest queue-overwrites-prior-queue
  (st/queue-prefix-message! "first")
  (st/queue-prefix-message! "second")
  (is (= "second" (st/take-prefix-message!))))
