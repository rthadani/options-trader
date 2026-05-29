(ns options-trader.tui.render
  "Pure functions that turn a state map + terminal dimensions into a string
   for charm.clj's view function."
  (:require [clojure.string :as str]))

(defn- pad
  [s n]
  (let [s (str s)
        len (count s)]
    (cond
      (= len n) s
      (> len n) (subs s 0 n)
      :else     (str s (apply str (repeat (- n len) \space))))))

(defn- fnum
  [n decimals]
  (cond
    (nil? n)         ""
    (number? n)      (format (str "%." decimals "f") (double n))
    :else            (str n)))

(defn- fpnl
  [n]
  (cond
    (nil? n)  ""
    (zero? n) "0.00"
    (pos? n)  (str "+" (fnum n 2))
    :else     (fnum n 2)))

(defn- fpct
  [unrealized avg-cost qty]
  (let [base (when (and avg-cost qty (not (zero? avg-cost)) (not (zero? qty)))
               (* (Math/abs (double avg-cost)) (Math/abs (double qty))))]
    (if (and unrealized base (not (zero? base)))
      (str (fnum (* 100.0 (/ (double unrealized) base)) 1) "%")
      "")))

(defn layout
  [height]
  (let [usable        (- height 2)
        portfolio-h   (max 8 (int (* usable 0.40)))
        chat-h        (- usable portfolio-h)]
    {:portfolio-top    0
     :portfolio-bottom (dec portfolio-h)
     :chat-top         portfolio-h
     :chat-bottom      (+ portfolio-h chat-h -1)
     :input-row        (+ portfolio-h chat-h)
     :status-row       (inc (+ portfolio-h chat-h))}))

(defn- portfolio-header [state width]
  (let [acct  (or (:account-id state) "—")
        tws   (case (:tws-status state)
                :connected     "connected"
                :disconnected  "disconnected"
                :connecting    "connecting")
        left  (str " Portfolio")
        right (str "account: " acct "  TWS: " tws)]
    (pad (str left
              (apply str (repeat (max 0 (- width (count left) (count right))) \space))
              right)
         width)))

(defn- portfolio-table-header [width]
  (pad " Sym       Qty       Mkt        Avg       P&L $       P&L %" width))

(defn- format-position-row [{:keys [symbol opt-right strike expiry qty avg-cost
                                    market-value unrealized-pnl]}
                            width]
  (let [sym-label (str symbol
                       (when (and opt-right (seq (str opt-right)))
                         (str " " opt-right (fnum strike 0) " " expiry)))
        mkt-px    (when (and market-value qty (not (zero? qty)))
                    (/ (double market-value) (double qty)))
        pnl-pct   (fpct unrealized-pnl avg-cost qty)]
    (pad (format " %-9s %5s   %10s %10s   %8s   %8s"
                 (subs sym-label 0 (min 9 (count sym-label)))
                 (if qty (str qty) "")
                 (fnum mkt-px 2)
                 (fnum avg-cost 2)
                 (fpnl unrealized-pnl)
                 pnl-pct)
         width)))

(defn- portfolio-footer [{:keys [account-summary]} width]
  (let [{:keys [net-liq cash day-pl buying-power]} account-summary]
    (pad (str " NetLiq " (fnum net-liq 0)
              "   Cash " (fnum cash 0)
              "   BP " (fnum buying-power 0)
              "   Day P&L " (fpnl day-pl))
         width)))

(defn- portfolio-lines [state width portfolio-h]
  (let [max-positions (max 0 (- portfolio-h 3))
        positions     (take max-positions (:positions state))]
    (vec
     (concat
      [(portfolio-header state width)]
      [(portfolio-table-header width)]
      (for [[idx p] (map-indexed vector positions)]
        (format-position-row p width))
      (for [idx (range (count positions) max-positions)]
        (pad "" width))
      [(portfolio-footer state width)]))))

(defn- chat-header [state width]
  (let [left  " Research"
        right (str "scope: " (name (:scope state))
                   "  " (name (:provider state :claude)) ":" (:model state))]
    (pad (str left
              (apply str (repeat (max 0 (- width (count left) (count right))) \space))
              right)
         width)))

(defn- activity-header [width]
  (pad " Activity" width))

(defn- wrap-text [s n]
  (let [lines (str/split-lines (or s ""))]
    (vec
     (mapcat
      (fn [line]
        (if (<= (count line) n)
          [line]
          (loop [remaining line acc []]
            (if (<= (count remaining) n)
              (conj acc remaining)
              (recur (subs remaining n) (conj acc (subs remaining 0 n)))))))
      lines))))

(defn- role-prefix [role]
  (case role
    :user      ">"
    :assistant " "
    :system    "*"
    :tool      "#"
    :thinking  "."
    "       "))

(defn- chat-message-lines [{:keys [role text]} width]
  (let [prefix (role-prefix role)
        inner-width (max 1 (- width (count prefix) 2))
        wrapped (wrap-text text inner-width)]
    (vec
     (map-indexed
      (fn [i ln]
        (str " " (if (zero? i) prefix (apply str (repeat (count prefix) \space))) ln))
      wrapped))))

(defn- pane-lines
  [header items width h]
  (let [lines (cons header (mapcat #(chat-message-lines % width) items))
        shown (take-last h lines)]
    (concat shown (repeat (max 0 (- h (count shown))) ""))))

(defn- chat-lines [state width chat-h]
  (let [left-w  (max 20 (int (* width 0.62)))
        right-w (max 1 (- width left-w 3))
        conv    (vec (pane-lines (chat-header state left-w) (:messages state) left-w chat-h))
        act     (vec (pane-lines (activity-header right-w) (:activity state) right-w chat-h))]
    (vec
     (for [idx (range chat-h)]
       (let [l (pad (nth conv idx "") left-w)
             r (pad (nth act idx "") right-w)]
         (str l " | " r))))))

(defn- input-line [state width]
  (let [prompt (if (:streaming? state) "..." ">")
        text   (:input state)
        line   (str prompt text)]
    (pad line width)))

(defn- status-line [state width]
  (let [s (or (:status-line state)
              (if (:streaming? state)
                "claude is thinking..."
                "type to chat - slash commands: /quit /refresh /model /sessions"))]
    (pad (str " " s) width)))

(defn render
  [state width height]
  (let [{:keys [portfolio-bottom chat-top]
         input-r :input-row status-r :status-row}
        (layout height)
        portfolio-h (inc portfolio-bottom)
        chat-h      (inc (- chat-top portfolio-bottom))]
    (str/join "\n"
              (concat
               (take portfolio-h (portfolio-lines state width portfolio-h))
               (take chat-h (chat-lines state width chat-h))
               [(input-line state width)]
               [(status-line state width)]))))