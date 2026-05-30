(ns options-trader.tui.state-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [options-trader.tui.state :as st]))

(use-fixtures :each (fn [f] (st/reset-state!) (f) (st/reset-state!)))

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