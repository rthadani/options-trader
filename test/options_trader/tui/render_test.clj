(ns options-trader.tui.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [options-trader.tui.render :as render]
            [options-trader.tui.state  :as st]))

(defn- lines-with-index [s]
  (into [] (map-indexed vector (str/split-lines s))))

(deftest layout-divides-height-into-panels
  (let [{:keys [portfolio-bottom chat-top chat-bottom input-row status-row]}
        (render/layout 30)]
    (is (pos? portfolio-bottom))
    (is (= (inc portfolio-bottom) chat-top))
    (is (< chat-top chat-bottom))
    (is (= (inc chat-bottom) input-row))
    (is (= (inc input-row) status-row))))

(deftest render-returns-string
  (let [s "Portfolio Test"]
    (is (string? (render/render st/initial-state 80 30)))))

(deftest render-includes-portfolio-header
  (let [output (render/render st/initial-state 80 30)
        lines  (lines-with-index output)]
    (is (some #(str/includes? % "Portfolio") lines))
    (is (some #(str/includes? % "TWS") lines))))

(deftest render-includes-chat-header
  (let [output (render/render st/initial-state 80 30)
        lines  (lines-with-index output)
        {:keys [chat-top]} (render/layout 30)]
    (is (some #(str/includes? % "Research") lines))))

(deftest render-shows-tws-disconnected-by-default
  (let [output (render/render st/initial-state 80 30)]
    (is (str/includes? output "disconnected"))))

(deftest render-shows-tws-connected-when-state-says-so
  (let [s      (assoc st/initial-state :tws-status :connected)
        output (render/render s 80 30)]
    (is (str/includes? output "connected"))
    (is (not (str/includes? output "disconnected")))))

(deftest render-shows-positions
  (let [s      (assoc st/initial-state
                      :positions
                      [{:symbol "AAPL" :qty 100 :avg-cost 185.20
                        :market-value 22050.0 :unrealized-pnl 3530.0}])
        output (render/render s 80 30)]
    (is (str/includes? output "AAPL"))
    (is (str/includes? output "+3530"))))

(deftest render-shows-account-summary-in-footer
  (let [s      (assoc st/initial-state
                      :account-summary {:net-liq 250000.0 :cash 42000.0
                                        :buying-power 84000.0 :day-pl 1570.0})
        output (render/render s 80 30)]
    (is (str/includes? output "NetLiq"))
    (is (str/includes? output "+1570"))))

(deftest render-shows-user-and-assistant-messages
  (let [s      (assoc st/initial-state
                      :messages [{:role :user :text "hi"}
                                 {:role :assistant :text "hello back"}])
        output (render/render s 80 30)]
    (is (str/includes? output "hi"))
    (is (str/includes? output "hello back"))))

(deftest render-shows-activity-column-beside-conversation
  (let [s      (assoc st/initial-state
                      :messages [{:role :assistant :text "ANSWERTEXT"}]
                      :activity [{:role :tool :text "TOOLNAME"}])
        output (render/render s 80 30)]
    (is (str/includes? output "ANSWERTEXT") "conversation text shows")
    (is (str/includes? output "TOOLNAME") "activity text shows")
    (is (str/includes? output "Activity") "activity header shows")
    (is (str/includes? output "|") "column divider present")))

(deftest render-includes-input-prompt
  (let [s      (assoc st/initial-state :input "foo")
        output (render/render s 80 30)]
    (is (str/includes? output "foo"))))

(deftest render-pads-every-row-to-terminal-width
  (let [output (render/render st/initial-state 80 30)
        lines  (str/split-lines output)]
    (doseq [line lines]
      (is (= 80 (count line))
          (str "row not padded to 80: " (pr-str line))))))