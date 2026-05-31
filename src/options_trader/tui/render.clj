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
      (> len n) (subs s 0 (max 1 n))
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

;; ── Portfolio section ─────────────────────────────────────────────────────────

(defn- portfolio-header [{:keys [account-id tws-status]} width]
  (let [tws  (case tws-status
               :connected    "🟢 connected"
               :disconnected "🔴 disconnected"
               :connecting   "🟡 connecting")]
    (pad (str " Portfolio  " account-id "  TWS: " tws) width)))

(defn- portfolio-table-header [width]
  (pad " Sym          Qty       Mkt       Avg       P&L        P&L%" width))

(defn- format-position [{:keys [symbol opt-right strike expiry qty avg-cost market-value unrealized-pnl]}
                        width]
  (let [label  (str symbol
                   (when (and opt-right (seq opt-right))
                     (str " " opt-right " $" (fnum strike 0))))
        mkt-px (when (and market-value qty (pos? qty))
                 (/ (double market-value) (double qty)))
        base   (when (and avg-cost qty (pos? qty))
                 (* (double avg-cost) (double qty)))
        pnl-pct (when (and unrealized-pnl base (pos? base))
                  (format "%.1f%%" (* 100.0 (/ (double unrealized-pnl) base))))]
    (pad (format " %-10s %6s %10s %10s %10s %8s"
                 (subs label 0 (min 10 (count label)))
                 (or (and qty (pos? qty) (str qty)) "")
                 (fnum mkt-px 2)
                 (fnum avg-cost 2)
                 (fpnl unrealized-pnl)
                 (or pnl-pct ""))
         width)))

(defn- portfolio-footer [{:keys [account-summary]} width]
  (let [{:keys [net-liq cash buying-power day-pl]} account-summary]
    (pad (str " NetLiq: " (fnum net-liq 0)
              "  Cash: " (fnum cash 0)
              "  BP: " (fnum buying-power 0)
              "  Day: " (fpnl day-pl))
         width)))

(defn- portfolio-lines [state width port-h]
  (let [header  [(portfolio-header state width)]
        theader [(portfolio-table-header width)]
        rows    (for [p (take (max 0 (- port-h 5)) (:positions state))]
                  (format-position p width))
        empty-rows (repeat (max 0 (- port-h 3 (count rows) 2)) (pad "" width))
        footer  [(portfolio-footer state width)]
        lines   (vec (concat header theader rows empty-rows footer))]
    (take port-h (concat lines (repeat (pad "" width))))))

;; ── Main render ───────────────────────────────────────────────────────────────

(defn render [state width height]
  (let [width     (max 40 width)
        height    (max 20 height)
        port-h    (min 12 (max 3 (int (* height 0.25))))
        footer-h  3
        chat-h    (max 8 (- height port-h footer-h))
        acct      (or (:account-id state) "—")
        scope-str (name (:scope state))
        model-str (str (:model state) " " (name (:provider state :claude)))
        top-line  (str "─" (apply str (repeat (dec width) \─)))
        bot-line  (str "─" (apply str (repeat (dec width) \─)))

        port-lines (vec (portfolio-lines state width port-h))
        all-lines  (mapcat #(message-lines % width) (:messages state))
        all-v      (vec all-lines)
        total-lines (count all-v)
        scroll    (min (:scroll-offset state 0) (max 0 (- total-lines chat-h)))
        visible   (subvec all-v (max 0 (- total-lines chat-h scroll))
                          (min total-lines (max 0 (- total-lines scroll))))
        chat-lines (vec (take chat-h (concat visible (repeat (pad "" width)))))
        in-focus? (= :input (:focus state))
        prompt     (if (:streaming? state) "··· " (if in-focus? "❯ " "▸ "))
        status     (or (:status-line state)
                       (if (:streaming? state)
                         "thinking..."
                         (if in-focus?
                           "type /help for commands  ──  tab to read"
                           "chat focused ──  ↑↓ pgup pgdn to scroll  ──  tab to type")))
        header     [(pad (str scope-str " | " model-str) width)]
        input-line (let [in     (:input state)
                        cur    (:cursor state)
                        padded (pad (str prompt in) width)
                        ansi-pos (+ (count prompt) cur)]
                    (if (and in-focus? (<= ansi-pos (count padded)))
                      (str (subs padded 0 ansi-pos)
                           "\033[7m"
                           (subs padded ansi-pos (min (inc ansi-pos) (count padded)))
                           "\033[0m"
                           (subs padded (min (inc ansi-pos) (count padded))))
                      padded))
        footer     [bot-line
                    input-line
                    (pad (str " " status) width)]]
    (str/join "\n"
              (concat
               header
               port-lines
               [top-line]
               (for [ln chat-lines]
                 (pad ln width))
               [bot-line]
               footer))))