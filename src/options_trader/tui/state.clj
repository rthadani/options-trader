(ns options-trader.tui.state
  "Single source of truth for the TUI's mutable state. Render is a pure
   function of this atom plus the terminal dimensions."
  (:require [clojure.java.io :as io]
            [options-trader.paths :as paths]
            [options-trader.util :as util]))

(def ^:const max-input-history
  "Cap the persisted history to a sliding window so the file can't grow
   unbounded over months of use. Oldest entries drop off the front."
  500)

(def initial-state
  {:positions        []
   :account-summary  nil
   :account-id       nil
   :tws-status       :disconnected
   :messages         []
   :activity         []
   :input            ""
   :cursor           0
   :scope            :scratch
   :model            "claude-opus-4-5"
   :provider         :claude
   :agent            :claude
   :streaming?       false
   :exit?            false
   :status-line      nil
   :scroll-offset    0
   :focus            :input
   :additional-dirs  []
   :input-history    []
   :history-idx      nil
   ;; Carries a /compact summary into the next user message; consumed once.
   :prefix-message   nil
   :profile          nil
   :ibkr-config      nil
   ;; ── Terminal dims, refreshed on WINCH ──────────────────────────────────
   :width            80
   :height           24
   ;; ── IB streaming bookkeeping (moved here from scattered defonces) ──────
   :stream {:pnl-rid          nil
            :pnl-single-rids  {}              ;; conid → req-id
            :counters         {:update-portfolio      0
                               :update-account-value  0
                               :update-account-time   0
                               :account-download-end  0
                               :pnl                   0
                               :pnl-single            0
                               :other                 0
                               :last-pnl-at           nil}}})

(defonce state (atom initial-state))

(defn reset-state! []
  (reset! state initial-state))

(defn append-activity!
  "Append {:role :thinking|:tool|:system :text \"...\"} to the activity stream
   (the right-hand column — what claude is doing, kept out of the conversation)."
  [role text]
  (swap! state update :activity
         (fn [a] (conj (vec a) {:role role :text text}))))

(defn append-chat!
  "Append directly to the visible chat, bypassing the activity-stream redirect.
   Use this for slash-command feedback the user must see."
  [role text]
  (swap! state update :messages
         (fn [msgs] (conj (vec msgs) {:role role :text text}))))

(defn append-message!
  "Append to the conversation. :system messages are routed to the activity
   stream so status/log noise doesn't flood the chat."
  [role text]
  (if (= role :system)
    (append-activity! :system text)
    (append-chat! role text)))

(defn append-to-last-assistant!
  "Append text to the most recent :assistant message, or start a new one
   if the last message isn't from the assistant. Used while streaming."
  [chunk]
  (swap! state update :messages
         (fn [msgs]
           (let [v (vec msgs)
                 i (dec (count v))
                 last-msg (when (>= i 0) (nth v i))]
             (if (= :assistant (:role last-msg))
               (assoc v i (update last-msg :text str chunk))
               (conj v {:role :assistant :text chunk}))))))

(defn set-status! [s]
  (swap! state assoc :status-line s))

(defn set-streaming! [b]
  (swap! state assoc :streaming? b))

(defn queue-prefix-message!
  "Store a string to prepend to the next user message. /compact uses this
   to carry a summary across the session reset."
  [s]
  (swap! state assoc :prefix-message s))

(defn take-prefix-message!
  "Atomic read-and-clear of :prefix-message. Call once per turn."
  []
  (let [[before _] (swap-vals! state assoc :prefix-message nil)]
    (:prefix-message before)))

(defn set-input! [s]
  (swap! state assoc :input s :cursor (count s)))

(defn clear-input! []
  (swap! state assoc :input "" :cursor 0))

(defn set-tws-status! [k]
  (swap! state assoc :tws-status k))

(defn set-portfolio! [{:keys [positions account-summary account-id]}]
  (swap! state assoc
         :positions       (or positions [])
         :account-summary account-summary
         :account-id      account-id))

(defn- positions-match?
  "Two position rows refer to the same position when EITHER their conids
   match (preferred — unambiguous), OR their (symbol, opt-right, strike,
   expiry) tuples match. The OR lets a stream event (which has :conid) merge
   with an existing DB-loaded row (which doesn't carry :conid in the schema)."
  [a b]
  (or (and (:conid a) (:conid b) (= (:conid a) (:conid b)))
      (= [(:symbol a) (:opt-right a) (:strike a) (:expiry a)]
         [(:symbol b) (:opt-right b) (:strike b) (:expiry b)])))

(defn apply-pnl-single!
  "Apply a reqPnLSingle :pnl-single event — per-position live PnL + market
   value. Updates the matching :positions row by :conid. Uses pick-style key
   defaulting because ib-re-actor splits PNL on each capital (:daily-pn-l
   etc.). The :value field is the broker's live market value — exactly what
   updatePortfolio is lazy about delivering."
  [ev]
  (let [conid     (:conid ev)
        daily     (or (:daily-pnl ev)      (:daily-pn-l ev))
        unreal    (or (:unrealized-pnl ev) (:unrealized-pn-l ev))
        realized  (or (:realized-pnl ev)   (:realized-pn-l ev))
        value     (or (:value ev) (:position-value ev))
        qty       (or (:position ev) (:pos ev))
        num       (fn [v] (when (number? v) (double v)))]
    (when conid
      (swap! state
             (fn [s]
               (let [v   (vec (or (:positions s) []))
                     idx (first (keep-indexed
                                  (fn [i p] (when (= conid (:conid p)) i)) v))]
                 (-> s
                     (cond->
                       idx
                       (update :positions
                               (fn [vec']
                                 (let [row (nth vec' idx)
                                       row' (cond-> row
                                              (num value)    (assoc :market-value (num value))
                                              (num unreal)   (assoc :unrealized-pnl (num unreal))
                                              (num realized) (assoc :realized-pnl (num realized))
                                              (num daily)    (assoc :daily-pnl (num daily))
                                              (num qty)      (assoc :qty (long (num qty))))]
                                   (assoc vec' idx row')))))
                     (assoc :portfolio-updated-at (System/currentTimeMillis)))))))))

(defn upsert-position!
  "Stream-friendly position update. Upserts in :positions by either-or
   match (see positions-match?) and drops the row when qty hits zero
   (IB reports closed positions with qty=0 one last time). Also bumps
   :portfolio-updated-at so the render can show a freshness indicator."
  [pos]
  (swap! state
         (fn [s]
           (let [v   (vec (or (:positions s) []))
                 idx (first (keep-indexed
                              (fn [i p] (when (positions-match? p pos) i)) v))
                 v'  (cond
                       (and idx (zero? (long (or (:qty pos) 0))))
                       (vec (concat (subvec v 0 idx) (subvec v (inc idx))))

                       idx
                       (assoc v idx pos)

                       (zero? (long (or (:qty pos) 0)))
                       v

                       :else
                       (conj v pos))]
             (assoc s
                    :positions v'
                    :portfolio-updated-at (System/currentTimeMillis))))))

(def ^:private account-value-key
  "Map IB's updateAccountValue 'key' strings to our :account-summary keys.
   Only fields the render or downstream code reads are mapped; everything
   else is ignored so we don't bloat state.

   Note: NOT mapping IB's 'UnrealizedPnL'/'RealizedPnL' to :day-pl — those
   are TOTAL unrealized/realized across all positions, NOT today's P&L.
   Use reqPnL for the broker's true daily P&L (mapped via update-pnl!)."
  {"NetLiquidation"      :net-liq
   "TotalCashValue"      :cash
   "AvailableFunds"      :available-funds
   "BuyingPower"         :buying-power
   "ExcessLiquidity"     :excess-liquidity
   "GrossPositionValue"  :gross-position-value
   "UnrealizedPnL"       :unrealized-pl-total
   "RealizedPnL"         :realized-pl-total
   "InitMarginReq"       :init-margin
   "MaintMarginReq"      :maint-margin
   "FullInitMarginReq"   :full-init-margin
   "FullMaintMarginReq"  :full-maint-margin})

(defn update-pnl!
  "Apply a reqPnL :pnl event to :account-summary. Maps daily P&L → :day-pl
   (the broker's true daily figure, not 'sum of unrealized'). ib-re-actor's
   kebab converter splits `dailyPNL` on each capital so the keys arrive as
   `:daily-pn-l` (double dash) — read both forms defensively."
  [ev]
  (let [daily      (or (:daily-pnl ev)      (:daily-pn-l ev))
        unrealized (or (:unrealized-pnl ev) (:unrealized-pn-l ev))
        realized   (or (:realized-pnl ev)   (:realized-pn-l ev))
        num        (fn [v] (when (number? v) (double v)))]
    (swap! state
           (fn [s]
             (-> s
                 (update :account-summary
                         (fn [m]
                           (let [m (or m {})]
                             (cond-> m
                               (num daily)      (assoc :day-pl       (num daily))
                               (num unrealized) (assoc :unrealized-pl (num unrealized))
                               (num realized)   (assoc :realized-pl   (num realized))))))
                 (assoc :portfolio-updated-at (System/currentTimeMillis)))))))

(defn update-account-value!
  "Apply an updateAccountValue event. Parses :value to a double when possible
   and merges into :account-summary. Bumps :account-updated-at (separate from
   :portfolio-updated-at) so the freshness indicator reflects position value
   movement, not the harmless 3-minute account-time pings."
  [{:keys [key value]}]
  (when-let [k (get account-value-key key)]
    (let [n (try (Double/parseDouble (str value)) (catch Throwable _ nil))]
      (when (some? n)
        (swap! state
               (fn [s]
                 (-> s
                     (update :account-summary
                             (fn [m] (assoc (or m {}) k n)))
                     (assoc :account-updated-at (System/currentTimeMillis)))))))))

(defn quit! []
  (swap! state assoc :exit? true))

(defn toggle-focus! []
  (swap! state update :focus #(if (= :input %) :chat :input)))

(defn persist-input-history!
  "Write the current history vector to <config-root>/input-history.edn.
   Best-effort — a failed write is logged-and-swallowed so a read-only
   config dir doesn't kill the TUI mid-session."
  []
  (let [path (paths/input-history-file)
        hist (:input-history @state)]
    (util/safe-spit path (pr-str hist))))

(defn load-input-history!
  "Restore the input-history vector from disk on TUI startup. Missing or
   malformed file → start empty, no crash."
  []
  (let [path (paths/input-history-file)]
    (when-let [hist (and (.exists (io/file path))
                         (util/safe-edn-read (slurp path)))]
      (when (vector? hist)
        (swap! state assoc :input-history hist :history-idx nil)))))

(defn persist-tui-prefs!
  "Write current agent/model/provider to <config-root>/tui-prefs.edn so the
   next TUI launch resumes in the same configuration."
  []
  (let [path (paths/tui-prefs-file)
        s    @state
        prefs {:agent    (:agent s)
               :model    (:model s)
               :provider (:provider s)}]
    (util/safe-spit path (pr-str prefs))))

(defn load-tui-prefs!
  "Restore agent/model/provider from disk on TUI startup. Missing or
   malformed file → keep initial defaults."
  []
  (let [path (paths/tui-prefs-file)]
    (when-let [prefs (and (.exists (io/file path))
                          (util/safe-edn-read (slurp path)))]
      (when (map? prefs)
        (swap! state merge
               (cond-> {}
                 (:agent    prefs) (assoc :agent    (:agent prefs))
                 (:model    prefs) (assoc :model    (:model prefs))
                 (:provider prefs) (assoc :provider (:provider prefs))))))))

(defn push-input-history!
  "Append text to history, drop duplicates of the immediately-prior entry,
   trim to max-input-history, reset the recall cursor, and persist."
  [text]
  (swap! state (fn [s]
                 (let [hist  (:input-history s)
                       last' (peek hist)
                       hist' (if (= text last')
                               hist
                               (conj hist text))
                       hist' (if (> (count hist') max-input-history)
                               (subvec hist' (- (count hist') max-input-history))
                               hist')]
                   (-> s
                       (assoc :input-history hist')
                       (assoc :history-idx nil)))))
  (persist-input-history!))

(defn set-scroll-offset! [n messages chat-h]
  (swap! state assoc :scroll-offset
         (max 0 (min n (max 0 (- (count messages) chat-h))))))

(defn adjust-scroll!
  "Bump the scroll offset by `delta` (positive = scroll back in history,
   negative = scroll toward newest). Lower bound clamped to 0 here; the
   upper bound is enforced in render where the wrapped line count is known.
   Pre-clamping by (count messages) used to silently swallow scroll attempts
   when a single long response wrapped into many visible lines."
  [delta]
  (swap! state update :scroll-offset
         (fn [cur] (max 0 (+ (or cur 0) delta)))))

(defn recall-prev! []
  (swap! state (fn [s]
                 (let [hist (:input-history s)
                       hlen (count hist)]
                   (if (zero? hlen)
                     s
                     (let [idx (or (:history-idx s) hlen)
                           new-idx (max 0 (dec idx))
                           text   (if (= new-idx hlen) "" (nth hist new-idx))]
                       (assoc s :history-idx new-idx :input text :cursor (count text))))))))

(defn recall-next! []
  (swap! state (fn [s]
                 (let [hist (:input-history s)
                       hlen (count hist)]
                   (if (or (zero? hlen) (nil? (:history-idx s)))
                     s
                     (let [new-idx (inc (:history-idx s))]
                       (if (>= new-idx hlen)
                         (assoc s :history-idx nil :input "" :cursor 0)
                         (let [text (nth hist new-idx)]
                           (assoc s :history-idx new-idx :input text :cursor (count text))))))))))
