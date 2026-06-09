(ns options-trader.tui.render
  "Pure functions that turn a state map + terminal dimensions into a string
   for charm.clj's view function."
  (:require [clojure.string :as str]
            [options-trader.tui.markdown :as md]
            [options-trader.tui.watchlist :as watchlist]))

(defn- pad [s n]
  (let [s    (str (or s ""))
        len  (count s)
        diff (- n len)]
    (cond
      (= len n) s
      (> len n) (subs s 0 (max 1 n))
      :else     (str s (apply str (repeat diff \space))))))

(defn- pad-ansi
  "Like pad but ANSI-aware. Never truncates (truncating mid-escape would
   leave the terminal stuck in bold/dim/colour state). Reset trailer added
   so any unterminated span at line end doesn't bleed into the next line."
  [s n]
  (let [s    (str (or s ""))
        v    (md/visible-length s)
        diff (max 0 (- n v))]
    (str s md/RESET (apply str (repeat diff \space)))))

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
    (let [prefix   (role-prefix role)
          inner-w  (max 1 (- width (count prefix) 3))
          ;; Assistant content goes through the markdown renderer for bold /
          ;; italic / headers / code / tables. Everything else stays plain so
          ;; user input + system messages render exactly as typed.
          wrapped  (if (= role :assistant)
                     (md/render text inner-w)
                     (wrap-text text inner-w))
          indent   (apply str (repeat (+ 2 (count prefix)) \space))]
      (map-indexed
       (fn [i ln]
         (str (if (zero? i) (str prefix " ") indent) ln))
       wrapped))))

;; ── Portfolio section ─────────────────────────────────────────────────────────

(defn- portfolio-header [{:keys [account-id tws-status portfolio-updated-at]} width]
  (let [tws  (case tws-status
               :connected    "🟢 connected"
               :disconnected "🔴 disconnected"
               :connecting   "🟡 connecting"
               "—")
        ;; Show how stale the stream is so a frozen subscription is visible
        ;; at a glance — without this the user has to /stream-stats to tell.
        age  (when (and portfolio-updated-at (= tws-status :connected))
               (let [s (long (/ (- (System/currentTimeMillis)
                                   (long portfolio-updated-at))
                                1000))]
                 (cond
                   (< s 5)   "live"
                   (< s 60)  (str s "s ago")
                   (< s 600) (str (long (/ s 60)) "m ago")
                   :else     "stale")))]
    (pad (str " Portfolio  " account-id "  TWS: " tws
              (when age (str "  · stream: " age)))
         width)))

(defn- portfolio-table-header [width]
  (pad " Sym              Qty       Mkt       Avg       P&L        P&L%" width))

(defn- short-expiry
  "Compact MMdd string for an option expiry LocalDate, or nil for the
   1900-01-01 sentinel used on stock positions."
  [d]
  (when (and (instance? java.time.LocalDate d)
             (>= (.getYear ^java.time.LocalDate d) 2000))
    (.format ^java.time.LocalDate d
             (java.time.format.DateTimeFormatter/ofPattern "MMdd"))))

(defn- format-position [{:keys [symbol opt-right strike expiry qty avg-cost market-value unrealized-pnl]}
                        width]
  (let [exp    (short-expiry expiry)
        ;; For options, pack expiry + right + strike into the symbol slot
        ;; ("NVDA 0117C200") so the user can tell which leg of a chain a
        ;; row is — without it, every option on the same underlier reads
        ;; identically. Stocks get just the ticker.
        label  (cond
                 (and opt-right (seq opt-right) exp)
                 (str symbol " " exp opt-right (some-> strike long))
                 (and opt-right (seq opt-right))
                 (str symbol " " opt-right "$" (fnum strike 0))
                 :else symbol)
        mkt-px (when (and market-value qty (pos? qty))
                 (/ (double market-value) (double qty)))
        base   (when (and avg-cost qty (pos? qty))
                 (* (double avg-cost) (double qty)))
        pnl-pct (when (and unrealized-pnl base (pos? base))
                  (format "%.1f%%" (* 100.0 (/ (double unrealized-pnl) base))))]
    (pad (format " %-14s %6s %10s %10s %10s %8s"
                 (subs label 0 (min 14 (count label)))
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
  (let [header     [(portfolio-header state width)]
        theader    [(portfolio-table-header width)]
        positions  (vec (:positions state))
        ;; Reserved chrome: header + table-header + footer = 3 lines.
        ;; A truncation hint takes one more line when positions overflow.
        row-budget (max 0 (- port-h 3))
        truncate?  (> (count positions) row-budget)
        hint-line  (when truncate?
                     [(pad (format "  +%d more — /portfolio to see all"
                                   (- (count positions) (max 0 (dec row-budget))))
                           width)])
        shown      (if truncate? (max 0 (dec row-budget)) row-budget)
        rows       (mapv #(format-position % width) (take shown positions))
        empty-rows (repeat (max 0 (- row-budget (count rows) (if truncate? 1 0)))
                           (pad "" width))
        footer     [(portfolio-footer state width)]
        lines      (vec (concat header theader rows hint-line empty-rows footer))]
    (take port-h (concat lines (repeat (pad "" width))))))

;; ── Main render ───────────────────────────────────────────────────────────────

(defn- render-input-lines
  "Wrap prompt + input to width and apply cursor inversion when focused.
   Returns a vector of strings, each ≤ width chars. Always at least one line."
  [{:keys [in cursor in-focus? prompt width]}]
  (let [combined  (str prompt in)
        wrapped   (let [w (wrap-text combined width)]
                    (if (seq w) w [""]))
        cur-off   (+ (count prompt) (or cursor 0))
        cur-row   (min (dec (count wrapped)) (quot cur-off width))
        cur-col   (rem cur-off width)]
    (vec
     (map-indexed
      (fn [i ln]
        (let [padded (pad ln width)]
          (if (and in-focus? (= i cur-row) (<= cur-col (count padded)))
            (str (subs padded 0 cur-col)
                 "\033[7m"
                 (subs padded cur-col (min (inc cur-col) (count padded)))
                 "\033[0m"
                 (subs padded (min (inc cur-col) (count padded))))
            padded)))
      wrapped))))

(defn render [state width height]
  (let [width     (max 40 width)
        height    (max 20 height)
        ;; Up to 40% of the terminal for the portfolio panel, capped at 20
        ;; rows so even a tall terminal still leaves space for chat. The hint
        ;; line in portfolio-lines signals when positions are truncated.
        port-h    (min 20 (max 3 (int (* height 0.4))))
        in-focus? (= :input (:focus state))
        prompt    (if (:streaming? state) "··· " (if in-focus? "❯ " "▸ "))
        input-lines (render-input-lines {:in        (:input state)
                                         :cursor    (:cursor state)
                                         :in-focus? in-focus?
                                         :prompt    prompt
                                         :width     width})
        input-h   (count input-lines)
        ;; Lines outside the chat area: port-h + top-line(1)
        ;;                              + bot-line(1) + input-h + status(1)
        ;; chat-h is whatever's left so the total fits exactly in height.
        chat-h    (max 4 (- height port-h input-h 3))
        top-line  (str "─" (apply str (repeat (dec width) \─)))
        bot-line  (str "─" (apply str (repeat (dec width) \─)))

        ;; The watchlist column lives to the right of the portfolio pane.
        ;; Only show it when the terminal is wide enough that the portfolio
        ;; still has at least 40 chars to work with — otherwise drop the
        ;; pane and give all the width back to the portfolio.
        wl-want   (watchlist/panel-width)
        show-wl?  (>= (- width wl-want 1) 40)
        port-w    (if show-wl? (- width wl-want 1) width)
        port-only (vec (portfolio-lines state port-w port-h))
        port-lines (if-not show-wl?
                     port-only
                     (let [wl (watchlist/panel-lines state port-h)]
                       (mapv (fn [p w] (str p "│" w)) port-only wl)))
        all-lines  (mapcat #(message-lines % width) (:messages state))
        all-v      (vec all-lines)
        total-lines (count all-v)
        scroll    (min (:scroll-offset state 0) (max 0 (- total-lines chat-h)))
        visible   (subvec all-v (max 0 (- total-lines chat-h scroll))
                          (min total-lines (max 0 (- total-lines scroll))))
        chat-lines (vec (take chat-h (concat visible (repeat (pad "" width)))))
        agent-tag (let [agent-kw (:agent state :claude)
                        agent    (name agent-kw)
                        provider (name (:provider state :claude))
                        model    (or (:model state) "—")
                        scope    (:scope state :scratch)
                        scope-prefix (when (not= :scratch scope)
                                       (str "[" (name scope) "] "))]
                    ;; Provider is only meaningful for the claude agent (it
                    ;; selects which Anthropic-compat endpoint to hit). For pi
                    ;; the concept is internal — show just `pi · model`.
                    (str scope-prefix
                         (cond
                           (= agent-kw :pi)        (str "pi · " model)
                           (= "claude" provider)   (str "claude · " model)
                           :else                   (str "claude/" provider " · " model))))
        help-msg  (cond
                    (:streaming? state)
                    "esc to cancel"
                    in-focus?
                    "type /help  ──  ↑/↓ history  ──  esc to cancel  ──  tab to read"
                    :else
                    "chat focused  ──  ↑↓ pgup pgdn scroll  ──  tab to type")
        status    (or (:status-line state)
                      (if (:streaming? state)
                        (str agent-tag "  ──  thinking...  ──  esc to cancel")
                        (str agent-tag "  ──  " help-msg)))
        footer    (concat [bot-line] input-lines [(pad (str " " status) width)])]
    (str/join "\n"
              (concat
               port-lines
               [top-line]
               (for [ln chat-lines]
                 (pad-ansi ln width))
               footer))))