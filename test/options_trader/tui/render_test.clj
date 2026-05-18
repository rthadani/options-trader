(ns options-trader.tui.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.tui.render :as render]
            [options-trader.tui.state  :as st]))

(defn- text-by-row [ops]
  (into {} (map (juxt :row :text) ops)))

(deftest layout-divides-height-into-panels
  (let [{:keys [portfolio-bottom chat-top chat-bottom input-row status-row]}
        (render/layout 30)]
    (is (pos? portfolio-bottom))
    (is (= (inc portfolio-bottom) chat-top))
    (is (< chat-top chat-bottom))
    (is (= (inc chat-bottom) input-row))
    (is (= (inc input-row) status-row))))

(deftest render-includes-portfolio-header
  (let [ops (render/render st/initial-state 80 30)
        m   (text-by-row ops)]
    (is (.contains (str (get m 0)) "Portfolio"))
    (is (.contains (str (get m 0)) "TWS"))))

(deftest render-includes-chat-header
  (let [ops (render/render st/initial-state 80 30)
        {:keys [chat-top]} (render/layout 30)]
    (is (.contains (str (get (text-by-row ops) chat-top))
                   "Research"))))

(deftest render-shows-tws-disconnected-by-default
  (let [ops (render/render st/initial-state 80 30)
        m   (text-by-row ops)]
    (is (.contains (str (get m 0)) "disconnected"))))

(deftest render-shows-tws-connected-when-state-says-so
  (let [s   (assoc st/initial-state :tws-status :connected)
        ops (render/render s 80 30)
        m   (text-by-row ops)]
    (is (.contains (str (get m 0)) "connected"))
    (is (not (.contains (str (get m 0)) "disconnected")))))

(deftest render-shows-positions
  (let [s   (assoc st/initial-state
                   :positions
                   [{:symbol "AAPL" :qty 100 :avg-cost 185.20
                     :market-value 22050.0 :unrealized-pnl 3530.0}])
        ops (render/render s 80 30)
        m   (text-by-row ops)]
    (is (some #(.contains (str %) "AAPL") (vals m)))
    (is (some #(.contains (str %) "+3530") (vals m)))))

(deftest render-shows-account-summary-in-footer
  (let [s (assoc st/initial-state
                 :account-summary {:net-liq 250000.0 :cash 42000.0
                                   :buying-power 84000.0 :day-pl 1570.0})
        ops (render/render s 80 30)
        m   (text-by-row ops)
        {:keys [portfolio-bottom]} (render/layout 30)
        footer (get m portfolio-bottom)]
    (is (.contains (str footer) "NetLiq"))
    (is (.contains (str footer) "+1570"))))

(deftest render-shows-user-and-assistant-messages
  (let [s (assoc st/initial-state
                 :messages [{:role :user      :text "hi"}
                            {:role :assistant :text "hello back"}])
        ops (render/render s 80 30)
        texts (mapv :text ops)]
    (is (some #(.contains (str %) "hi") texts))
    (is (some #(.contains (str %) "hello back") texts))))

(deftest render-includes-input-prompt
  (let [s   (assoc st/initial-state :input "foo")
        ops (render/render s 80 30)
        {:keys [input-row]} (render/layout 30)]
    (is (.contains (str (get (text-by-row ops) input-row)) "foo"))))

(deftest cursor-position-tracks-cursor-field
  (let [s   (assoc st/initial-state :input "hello" :cursor 3)
        {:keys [col]} (render/cursor-position s 30)]
    (is (= 5 col))))

(deftest render-pads-every-row-to-terminal-width
  (let [ops (render/render st/initial-state 80 30)]
    (doseq [{:keys [text]} ops]
      (is (= 80 (count text))
          (str "row not padded to 80: " (pr-str text))))))
