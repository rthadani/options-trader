(ns options-trader.tui.render
  "Pure functions that turn a state map + terminal dimensions into a vector
   of {:row N :col 0 :text \"…\"} draw calls. main.clj walks the result and
   issues the actual Lanterna putString operations."
  (:require [clojure.string :as str]))

(defn- pad
  "Truncate or right-pad s to exactly n cells."
  [s n]
  (let [s (str s)
        len (count s)]
    (cond
      (= len n) s
      (> len n) (subs s 0 n)
      :else     (str s (apply str (repeat (- n len) \space))))))

(defn- fnum
  "Format a number with a fixed number of decimals; nil/blank stays blank."
  [n decimals]
  (cond
    (nil? n)         ""
    (number? n)      (format (str "%." decimals "f") (double n))
    :else            (str n)))

(defn- fpnl
  "Format a P&L value with a leading sign; nil stays blank."
  [n]
  (cond
    (nil? n)  ""
    (zero? n) "0.00"
    (pos? n)  (str "+" (fnum n 2))
    :else     (fnum n 2)))

(defn- fpct
  "Format avg-cost-relative P&L percentage; needs avg-cost and unrealized."
  [unrealized avg-cost qty]
  (let [base (when (and avg-cost qty (not (zero? avg-cost)) (not (zero? qty)))
               (* (Math/abs (double avg-cost)) (Math/abs (double qty))))]
    (if (and unrealized base (not (zero? base)))
      (str (fnum (* 100.0 (/ (double unrealized) base)) 1) "%")
      "")))

;;; ── Layout ──────────────────────────────────────────────────────────────────

(defn layout
  "Compute row offsets for each region given terminal height + state."
  [height]
  (let [;; Reserve 2 rows for input + bottom border.
        usable        (- height 2)
        portfolio-h   (max 8 (int (* usable 0.40)))
        chat-h        (- usable portfolio-h)]
    {:portfolio-top    0
     :portfolio-bottom (dec portfolio-h)
     :chat-top         portfolio-h
     :chat-bottom      (+ portfolio-h chat-h -1)
     :input-row        (+ portfolio-h chat-h)
     :status-row       (inc (+ portfolio-h chat-h))}))

;;; ── Portfolio pane ──────────────────────────────────────────────────────────

(defn- portfolio-header [state width]
  (let [acct  (or (:account-id state) "—")
        tws   (case (:tws-status state)
                :connected     "● connected"
                :disconnected  "○ disconnected"
                :connecting    "◐ connecting")
        left  (str " Portfolio")
        right (str "account: " acct "  TWS: " tws " ")]
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

(defn- portfolio-rows [state width portfolio-h]
  (let [;; rows 0=header, 1=table header, 2..(portfolio-h-2)=positions,
        ;; (portfolio-h-1)=footer
        max-positions (max 0 (- portfolio-h 3))
        positions     (take max-positions (:positions state))]
    (vec
     (concat
      [{:row 0 :col 0 :text (portfolio-header state width)}
       {:row 1 :col 0 :text (portfolio-table-header width)}]
      (for [[idx p] (map-indexed vector positions)]
        {:row (+ 2 idx) :col 0 :text (format-position-row p width)})
      ;; Pad empty position rows with blank lines so old text doesn't ghost.
      (for [idx (range (count positions) max-positions)]
        {:row (+ 2 idx) :col 0 :text (pad "" width)})
      [{:row (dec portfolio-h) :col 0 :text (portfolio-footer state width)}]))))

;;; ── Chat pane ──────────────────────────────────────────────────────────────

(defn- chat-header [state width]
  (let [left  " Research"
        right (str "scope: " (name (:scope state))
                   "  model: " (:model state) " ")]
    (pad (str left
              (apply str (repeat (max 0 (- width (count left) (count right))) \space))
              right)
         width)))

(defn- wrap-text
  "Word-wrap text to width n. Returns vector of lines."
  [s n]
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
    :user      "› "
    :assistant ""
    :system    "[sys] "
    "      "))

(defn- chat-message-lines [{:keys [role text]} width]
  (let [prefix (role-prefix role)
        inner-width (max 1 (- width (count prefix) 2))
        wrapped (wrap-text text inner-width)]
    (vec
     (map-indexed
      (fn [i ln]
        (str " " (if (zero? i) prefix (apply str (repeat (count prefix) \space))) ln))
      wrapped))))

(defn- chat-rows [state width chat-top chat-bottom]
  (let [chat-h (inc (- chat-bottom chat-top))
        all-lines (mapcat #(chat-message-lines % width) (:messages state))
        all-lines (concat [(chat-header state width)] all-lines)
        ;; Show the tail so the newest message is visible.
        rendered  (take-last chat-h all-lines)
        ;; Pad up so the pane fills.
        padded    (concat rendered (repeat (- chat-h (count rendered)) ""))]
    (vec
     (for [[idx ln] (map-indexed vector padded)]
       {:row (+ chat-top idx) :col 0 :text (pad ln width)}))))

;;; ── Input + status ─────────────────────────────────────────────────────────

(defn- input-row [state width row]
  (let [prompt (if (:streaming? state) "⏳ " "› ")
        text   (:input state)
        line   (str prompt text)]
    {:row row :col 0 :text (pad line width)}))

(defn- status-row [state width row]
  (let [s (or (:status-line state)
              (if (:streaming? state)
                "claude is thinking…  (esc to interrupt — not yet wired)"
                "type to chat — slash commands: /quit /refresh /model /sessions"))]
    {:row row :col 0 :text (pad (str " " s) width)}))

;;; ── Top-level render ───────────────────────────────────────────────────────

(defn render
  "Pure function: returns a vector of draw operations for the given state
   and terminal dimensions. Each op is {:row :col :text}."
  [state width height]
  (let [{:keys [portfolio-bottom chat-top chat-bottom]
         input-r :input-row status-r :status-row}
        (layout height)
        portfolio-h (inc portfolio-bottom)]
    (vec
     (concat
      (portfolio-rows state width portfolio-h)
      (chat-rows     state width chat-top chat-bottom)
      [(input-row    state width input-r)
       (status-row   state width status-r)]))))

(defn cursor-position
  "Where to place the terminal cursor for the current input."
  [state height]
  (let [{:keys [input-row]} (layout height)
        prompt-width 2]
    {:row input-row
     :col (+ prompt-width (:cursor state 0))}))
