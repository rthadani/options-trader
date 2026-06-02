(ns options-trader.tui.state
  "Single source of truth for the TUI's mutable state. Render is a pure
   function of this atom plus the terminal dimensions."
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [options-trader.paths :as paths]))

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
   :profile          nil
   :ibkr-config      nil})

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
    (try
      (io/make-parents path)
      (spit path (pr-str hist))
      (catch Exception _ nil))))

(defn load-input-history!
  "Restore the input-history vector from disk on TUI startup. Missing or
   malformed file → start empty, no crash."
  []
  (let [path (paths/input-history-file)]
    (try
      (when (.exists (io/file path))
        (let [hist (edn/read-string (slurp path))]
          (when (vector? hist)
            (swap! state assoc :input-history hist :history-idx nil))))
      (catch Exception _ nil))))

(defn persist-tui-prefs!
  "Write current agent/model/provider to <config-root>/tui-prefs.edn so the
   next TUI launch resumes in the same configuration."
  []
  (let [path (paths/tui-prefs-file)
        s    @state
        prefs {:agent    (:agent s)
               :model    (:model s)
               :provider (:provider s)}]
    (try
      (io/make-parents path)
      (spit path (pr-str prefs))
      (catch Exception _ nil))))

(defn load-tui-prefs!
  "Restore agent/model/provider from disk on TUI startup. Missing or
   malformed file → keep initial defaults."
  []
  (let [path (paths/tui-prefs-file)]
    (try
      (when (.exists (io/file path))
        (let [prefs (edn/read-string (slurp path))]
          (when (map? prefs)
            (swap! state merge
                   (cond-> {}
                     (:agent    prefs) (assoc :agent    (:agent prefs))
                     (:model    prefs) (assoc :model    (:model prefs))
                     (:provider prefs) (assoc :provider (:provider prefs)))))))
      (catch Exception _ nil))))

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
