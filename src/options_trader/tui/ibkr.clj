(ns options-trader.tui.ibkr
  "Background IBKR connection + portfolio refresh for the TUI.

   This is the streaming surface for the TUI: a connect thread, a poller that
   re-runs portfolio.core/refresh! every few seconds, and slash-callable
   connect/disconnect helpers. Each refresh exercises ibkr's streaming
   req-positions / req-account-summary internally."
  (:require [clojure.core.async :as a]
            [options-trader.data.ibkr :as ibkr]
            [options-trader.portfolio.core :as portfolio]
            [options-trader.tui.state :as st]))

(defonce ^:private conn-atom (atom nil))
(defonce ^:private poller-atom (atom nil))

(def ^:private poll-interval-ms 15000)

;;; ── State helpers ──────────────────────────────────────────────────────────

(defn- log-message! [msg]
  (st/append-message! :system (str "[ibkr] " msg)))

;;; ── Refresh ────────────────────────────────────────────────────────────────

(defn refresh-from-ibkr! [ds account-id]
  (when-let [conn @conn-atom]
    (try
      (let [src (portfolio/->IbkrSource conn ds account-id)]
        (portfolio/refresh! src ds account-id)
        (let [store     (portfolio/->JdbcStore ds)
              positions (portfolio/read-positions store account-id)
              summary   (portfolio/read-account-summary store account-id)]
          (st/set-portfolio! {:positions       positions
                              :account-summary summary
                              :account-id      account-id})))
      (catch Throwable t
        (log-message! (str "refresh failed: " (.getMessage t)))))))

;;; ── Poller ─────────────────────────────────────────────────────────────────

(defn- stop-poller! []
  (when-let [stop @poller-atom]
    (a/close! stop)
    (reset! poller-atom nil)))

(defn- start-poller! [ds]
  (stop-poller!)
  (let [stop (a/chan)]
    (reset! poller-atom stop)
    (a/go-loop []
      (let [timeout (a/timeout poll-interval-ms)
            [_ ch]  (a/alts! [stop timeout])]
        (when (= ch timeout)
          (when-let [account-id (:account-id @st/state)]
            (a/thread (refresh-from-ibkr! ds account-id)))
          (recur))))))

;;; ── Connect / disconnect ───────────────────────────────────────────────────

(defn connect-async!
  "Spawn a background thread that connects to TWS, detects the account-id,
   does an initial portfolio refresh, and starts the polling loop. Updates
   :tws-status on the state atom throughout."
  [{:keys [host port client-id ds]}]
  (st/set-tws-status! :connecting)
  (log-message! (str "connecting to " host ":" port " client-id=" client-id))
  (a/thread
    (let [c (ibkr/connect! host port client-id)]
      (cond
        (or (nil? c) (= c :unavailable))
        (do (st/set-tws-status! :disconnected)
            (log-message! "TWS unreachable — staying with cached portfolio"))

        :else
        (do (reset! conn-atom c)
            (st/set-tws-status! :connected)
            (let [account (or (:account-id @st/state) (ibkr/default-account))]
              (when account
                (swap! st/state assoc :account-id account)
                (log-message! (str "connected; account=" account))
                (refresh-from-ibkr! ds account)
                (start-poller! ds))))))))

(defn disconnect! []
  (stop-poller!)
  (try (ibkr/disconnect!) (catch Throwable _))
  (reset! conn-atom nil)
  (st/set-tws-status! :disconnected)
  (log-message! "disconnected"))

(defn connected? []
  (some? @conn-atom))
