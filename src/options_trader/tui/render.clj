(ns options-trader.tui.render
  "Pure functions that turn a state map + terminal dimensions into a string
   for charm.clj's view function."
  (:require [clojure.string :as str]))

(defn- pad [s n]
  (let [s    (str (or s ""))
        len  (count s)
        diff (- n len)]
    (cond
      (= len n) s
      (> len n) (subs s 0 n)
      :else     (str s (apply str (repeat diff \space))))))

(defn- fnum [n decimals]
  (cond
    (nil? n)         ""
    (number? n)      (format (str "%." decimals "f") (double n))
    :else            (str n)))

(defn- fpnl [n]
  (cond
    (nil? n)  ""
    (zero? n) "0.00"
    (pos? n)  (str "+" (fnum n 2))
    :else     (fnum n 2)))

(defn- wrap-text [s width]
  (let [lines (str/split-lines (or s ""))]
    (vec
     (mapcat
      (fn [line]
        (if (<= (count line) width)
          [line]
          (loop [remaining line acc []]
            (if (<= (count remaining) width)
              (conj acc remaining)
              (recur (subs remaining width) (conj acc (subs remaining 0 width)))))))
      lines))))

(defn- role-prefix [role]
  (case role
    :user      "❯"
    :assistant " "
    :system    "*"
    :tool      "├"
    :thinking  "·"
    " "))

(defn- message-lines [{:keys [role text]} width]
  (when (and text (seq text))
    (let [prefix  (role-prefix role)
          inner-w (max 1 (- width (count prefix) 3))
          wrapped (wrap-text text inner-w)]
      (map-indexed
       (fn [i ln]
         (str (if (zero? i) (str prefix " ") (str (apply str (repeat (+ 2 (count prefix)) \space)))) ln))
       wrapped))))

(defn render [state width height]
  (let [header-h  3
        footer-h  3
        chat-h    (max 10 (- height header-h footer-h))
        acct      (or (:account-id state) "—")
        tws-status (case (:tws-status state)
                     :connected    "🟢 connected"
                     :disconnected "🔴 disconnected"
                     :connecting   "🟡 connecting")
        scope-str (name (:scope state))
        model-str (str (:model state) " " (name (:provider state :claude)))
        sep       (apply str (repeat width \─))
        all-lines (mapcat #(message-lines % width) (:messages state))
        shown     (vec (take-last chat-h all-lines))
        result    (vec (take chat-h (concat shown (repeat (pad "" width)))))
        prompt    (if (:streaming? state) "··· " "❯ ")
        status    (or (:status-line state)
                      (if (:streaming? state)
                        "thinking..."
                        "type /help for commands"))
        header    [(pad (str " " acct "  " tws-status "  |  " scope-str "  |  " model-str) width)
                   (pad "" width)
                   sep]
        footer    [sep
                   (pad (str prompt (:input state)) width)
                   (pad (str " " status) width)]]
    (str/join "\n" (concat header result footer))))
