(ns options-trader.tui.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [options-trader.tui.render :as render]
            [options-trader.tui.state  :as st]))

(defn- lines-with-index [s]
  (into [] (map-indexed vector (str/split-lines s))))

(deftest render-returns-string
  (is (string? (render/render st/initial-state 80 30))))

(deftest render-includes-portfolio-header
  (let [output (render/render st/initial-state 80 30)]
    (is (str/includes? output "Portfolio"))))

(deftest render-includes-tws-status
  (let [output (render/render st/initial-state 80 30)]
    (is (or (str/includes? output "connecting")
            (str/includes? output "connected")
            (str/includes? output "disconnected")))))

(deftest render-shows-tws-connected-when-state-says-so
  (let [s      (assoc st/initial-state :tws-status :connected)
        output (render/render s 80 30)]
    (is (str/includes? output "connected"))
    (is (not (str/includes? output "disconnected")))))

(deftest render-shows-positions
  (let [s      (assoc st/initial-state
                      :positions [{:symbol "AAPL" :qty 100 :avg-cost 185.20
                                   :market-value 22050.0 :unrealized-pnl 3530.0}])
        output (render/render s 80 30)]
    (is (str/includes? output "AAPL"))))

(deftest render-shows-account-summary
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

(deftest render-shows-tool-messages
  (let [s      (assoc st/initial-state
                      :messages [{:role :tool :text "TOOLNAME"}])
        output (render/render s 80 30)]
    (is (str/includes? output "TOOLNAME"))))

(deftest render-shows-long-messages-wrapped
  (let [long-text (apply str (repeat 100 "x"))
        s         (assoc st/initial-state
                         :messages [{:role :assistant :text long-text}])
        output    (render/render s 80 30)
        lines     (str/split-lines output)]
    (is (> (count lines) 10) "long message should wrap to multiple lines")))

(deftest render-includes-input-prompt
  (let [s      (assoc st/initial-state :input "foo")
        output (render/render s 80 30)
        clean  (str/replace output #"\033\[[0-9;]*[m]" "")]
    (is (str/includes? clean "foo"))))


(deftest render-has-portfolio-then-chat
  (let [output (render/render st/initial-state 80 30)
        lines  (lines-with-index output)]
    (is (some #(str/includes? % "Portfolio") lines))
    (is (some #(str/includes? % "❯") lines))))