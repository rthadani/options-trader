(ns options-trader.tui.state
  "Single source of truth for the TUI's mutable state. Render is a pure
   function of this atom plus the terminal dimensions.")

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
   :history-idx      nil})

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

(defn push-input-history! [text]
  (swap! state (fn [s]
                 (-> s
                     (update :input-history conj text)
                     (assoc :history-idx nil)))))

(defn set-scroll-offset! [n messages chat-h]
  (swap! state assoc :scroll-offset
         (max 0 (min n (max 0 (- (count messages) chat-h))))))

(defn adjust-scroll! [delta]
  (swap! state (fn [s]
                 (let [chat-h 80  ;; approximate, gets corrected on render
                       total   (count (:messages s))
                       current (:scroll-offset s 0)
                       new     (+ current delta)
                       capped  (max 0 (min new (max 0 (- total 10))))]
                   (assoc s :scroll-offset capped)))))

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
