(ns options-trader.tui.state
  "Single source of truth for the TUI's mutable state. Render is a pure
   function of this atom plus the terminal dimensions.")

(def initial-state
  {:positions        []
   :account-summary  nil
   :account-id       nil
   :tws-status       :disconnected
   :messages         []
   :input            ""
   :cursor           0
   :scope            :scratch
   :model            "claude-opus-4-5"
   :streaming?       false
   :exit?            false
   :status-line      nil
   :scroll-offset    0})

(defonce state (atom initial-state))

(defn reset-state! []
  (reset! state initial-state))

(defn append-message!
  "Append {:role :user|:assistant|:system :text \"...\"} to the conversation."
  [role text]
  (swap! state update :messages
         (fn [msgs] (conj (vec msgs) {:role role :text text}))))

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
