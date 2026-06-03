(ns options-trader.tui.ibkr
  "Background IBKR connection + live portfolio streaming for the TUI.

   Architecture:
     • connect-async! opens the TWS socket, captures the account-id from the
       managedAccounts handshake, does ONE initial refresh from DB+IB so the
       panel is populated immediately, then starts the streaming subscription
       and the health watchdog.
     • The streaming subscription uses reqAccountUpdates → :update-portfolio
       (live market values per position) + :update-account-value (NetLiq, BP,
       margin, etc.). Each event ticks state directly — no polling.
     • The health watchdog runs every health-interval-ms and corrects
       :tws-status when it diverges from the actual socket state. Catches
       silent drops the underlying TCP doesn't surface."
  (:require [clojure.core.async :as a]
            [options-trader.data.ibkr :as ibkr]
            [options-trader.portfolio.core :as portfolio]
            [options-trader.tui.state :as st]))

;; External resources / plumbing — NOT in the state atom:
;;   conn-atom         the live IB Java client object
;;   stream-stop-atom  account-code that owns the reqAccountUpdates stream
;;   health-stop-atom  core.async stop channel for the watchdog go-loop
;; Everything else (rids, counters, etc.) lives in st/state under :stream
;; — see tui.state/initial-state.
(defonce ^:private conn-atom (atom nil))
(defonce ^:private stream-stop-atom (atom nil))
(defonce ^:private health-stop-atom (atom nil))


(def ^:private health-interval-ms 2000)

;;; ── Logging helper ────────────────────────────────────────────────────────

(defn- log-message!
  "Surface an ibkr status line in the chat (NOT the activity stream, which
   the render never displays — that ate every diagnostic before this fix)."
  [msg]
  (st/append-chat! :system (str "[ibkr] " msg)))

;;; ── One-shot DB+IB refresh (used at startup and by /refresh) ──────────────

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

;;; ── Streaming portfolio + account updates ─────────────────────────────────

(defn- bump-counter! [k]
  (swap! st/state update-in [:stream :counters]
         (fn [c] (-> c (update k (fnil inc 0))))))

(defn- handle-pnl-event
  "Callback for the reqPnL subscription. We registered this against a known
   req-id, so any non-nil map that lands here is a PnL event regardless of
   what :type ib-re-actor labelled it with."
  [ev]
  (when (map? ev)
    (bump-counter! :pnl)
    (swap! st/state assoc-in [:stream :counters :last-pnl-at] (System/currentTimeMillis))
    (st/update-pnl! ev)))

(defn- handle-pnl-single-event
  "Callback for the reqPnLSingle subscription. The rid routing guarantees
   this is a per-position PnL event."
  [ev]
  (when (map? ev)
    (bump-counter! :pnl-single)
    (st/apply-pnl-single! ev)))

(defn- subscribe-pnl-single-for!
  "Subscribe a reqPnLSingle stream for one conid if we haven't already.
   The callback is wrapped in a closure that injects :conid into each event,
   because TWS's pnlSingle callback only delivers :req-id (the conid would
   otherwise be unrecoverable from the event payload)."
  [account-id conid]
  (let [rids (get-in @st/state [:stream :pnl-single-rids] {})]
    (when (and conid @conn-atom (not (contains? rids conid)))
      (try
        (let [tagged-cb (fn [ev]
                          (handle-pnl-single-event
                            (if (map? ev) (assoc ev :conid conid) ev)))
              rid (ibkr/req-pnl-single @conn-atom account-id conid tagged-cb)]
          (swap! st/state assoc-in [:stream :pnl-single-rids conid] rid))
        (catch Throwable t
          (log-message! (str "pnl-single subscribe failed for conid=" conid
                             ": " (.getMessage t))))))))

(defn- subscribe-pnl-singles-for-all-positions! [account-id]
  (doseq [pos (:positions @st/state)]
    (subscribe-pnl-single-for! account-id (:conid pos))))

(defn- cancel-all-pnl-singles! []
  (when @conn-atom
    (doseq [[_conid rid] (get-in @st/state [:stream :pnl-single-rids])]
      (try (ibkr/cancel-pnl-single @conn-atom rid) (catch Throwable _))))
  (swap! st/state assoc-in [:stream :pnl-single-rids] {}))

(defn- handle-stream-event
  "Stream callbacks get ONE event per invocation, plus a final nil when the
   subscription ends. (batch callbacks get the accumulated vector; streams
   don't — that's the whole point.)"
  [ev]
  (when (map? ev)
    (let [t    (:type ev)
          kind (case t
                 :update-portfolio     :update-portfolio
                 :update-account-value :update-account-value
                 :update-account-time  :update-account-time
                 :account-download-end :account-download-end
                 :other)]
      (bump-counter! kind)
      (case t
        :update-portfolio
        (do (st/upsert-position! (dissoc ev :type))
            ;; Subscribe per-position PnL stream so market_value updates
            ;; even when updatePortfolio gets stingy with price ticks.
            (when-let [acct (:account-id @st/state)]
              (subscribe-pnl-single-for! acct (:conid ev))))

        :update-account-value
        (st/update-account-value! ev)

        :account-download-end
        (when-let [acct (:account-id @st/state)]
          (subscribe-pnl-singles-for-all-positions! acct))

        nil))))

(defn- start-stream! [account-id]
  (when-let [conn @conn-atom]
    (try
      (ibkr/req-account-updates conn account-id handle-stream-event)
      (reset! stream-stop-atom account-id)
      (catch Throwable t
        (log-message! (str "stream subscribe failed: " (.getMessage t)))))
    (try
      (let [rid (ibkr/req-pnl conn account-id handle-pnl-event)]
        (swap! st/state assoc-in [:stream :pnl-rid] rid))
      (catch Throwable t
        (log-message! (str "reqPnL subscribe failed: " (.getMessage t)))))))

(defn- stop-stream! []
  (when-let [acct @stream-stop-atom]
    (try
      (when @conn-atom (ibkr/cancel-account-updates @conn-atom acct))
      (catch Throwable _))
    (reset! stream-stop-atom nil))
  (when-let [rid (get-in @st/state [:stream :pnl-rid])]
    (try (when @conn-atom (ibkr/cancel-pnl @conn-atom rid))
         (catch Throwable _))
    (swap! st/state assoc-in [:stream :pnl-rid] nil))
  (cancel-all-pnl-singles!))

;;; ── Health watchdog ───────────────────────────────────────────────────────

(def ^:private stale-stream-threshold-ms 45000)

(defn- maybe-resubscribe-stream! []
  (when-let [acct (:account-id @st/state)]
    (let [now      (System/currentTimeMillis)
          last-at  (or (:portfolio-updated-at @st/state) 0)
          stale?   (> (- now last-at) stale-stream-threshold-ms)]
      (when (and stale? (ibkr/is-connected?))
        ;; Silent re-subscribe — visible in the header's "stream: live/Ns ago"
        ;; indicator, no need to flood the chat. Failures still surface.
        (try (ibkr/cancel-account-updates @conn-atom acct) (catch Throwable _))
        (try (ibkr/req-account-updates @conn-atom acct handle-stream-event)
             (swap! st/state assoc :portfolio-updated-at now)
             (catch Throwable t
               (log-message! (str "re-subscribe failed: " (.getMessage t)))))))))

(def ^:private miss-threshold
  "ibkr/is-connected? can return false momentarily during heavy event
   traffic even when the socket is fine. Require N consecutive false
   readings before flipping to :disconnected so the indicator doesn't
   strobe between connected/disconnected."
  3)

(defn- start-health-watchdog! []
  (when @health-stop-atom
    (a/close! @health-stop-atom)
    (reset! health-stop-atom nil))
  (let [stop (a/chan)]
    (reset! health-stop-atom stop)
    (a/go-loop [misses 0]
      (let [timeout (a/timeout health-interval-ms)
            [_ ch]  (a/alts! [stop timeout])]
        (if (not= ch timeout)
          nil  ; stop channel triggered → exit go-loop
          (let [reported (= :connected (:tws-status @st/state))
                misses'  (if (ibkr/is-connected?)
                           (do (when (not reported) (st/set-tws-status! :connected))
                               0)
                           (let [n (inc misses)]
                             (when (and reported (>= n miss-threshold))
                               (st/set-tws-status! :disconnected)
                               (log-message! (str "TWS socket dropped — "
                                                  n " consecutive health checks failed")))
                             n))]
            ;; Even with the socket alive, the reqAccountUpdates subscription
            ;; can get torn down silently if TWS sends a stray :error tagged
            ;; with our rid. Re-subscribe when updates have stalled for too long.
            (try (maybe-resubscribe-stream!) (catch Throwable _))
            (recur misses')))))))

(defn- stop-health-watchdog! []
  (when-let [stop @health-stop-atom]
    (a/close! stop)
    (reset! health-stop-atom nil)))

;;; ── Connect / disconnect ──────────────────────────────────────────────────

(defn- await-account!
  "Poll for the account-id from the TWS managedAccounts handshake. The event
   is async so an immediate read of (ibkr/default-account) right after connect
   may return nil. Returns the account-id or nil after `deadline-ms`."
  [deadline-ms]
  (let [deadline (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (if-let [a (or (:account-id @st/state) (ibkr/default-account))]
        a
        (when (< (System/currentTimeMillis) deadline)
          (Thread/sleep 100)
          (recur))))))

(defn connect-async!
  "Spawn a background thread that connects to TWS, detects the account-id,
   does an initial refresh, starts the streaming subscription, and starts
   the health watchdog. The watchdog runs *immediately* so status reflects
   reality even if the connect attempt hangs or throws.

   All paths surface errors as chat-visible system messages — silent
   thread-death used to leave :tws-status stuck at :connecting."
  [{:keys [host port client-id ds]}]
  (st/set-tws-status! :connecting)
  (log-message! (str "connecting to " host ":" port " client-id=" client-id))
  ;; Start the watchdog FIRST. It owns the :tws-status transitions from
  ;; here on out, so even if the connect thread dies, status converges
  ;; to :disconnected within health-interval-ms.
  (start-health-watchdog!)
  (a/thread
    (try
      (let [c (try (ibkr/connect! host port client-id)
                   (catch Throwable t
                     (log-message! (str "connect threw: " (.getMessage t)))
                     :unavailable))]
        (cond
          (or (nil? c) (= c :unavailable))
          (do (st/set-tws-status! :disconnected)
              (log-message! "TWS unreachable — staying with cached portfolio"))

          :else
          (do (reset! conn-atom c)
              (st/set-tws-status! :connected)
              (if-let [account (await-account! 5000)]
                (do (swap! st/state assoc :account-id account)
                    (log-message! (str "connected; account=" account))
                    (try (refresh-from-ibkr! ds account)
                         (catch Throwable t
                           (log-message! (str "initial refresh failed: "
                                              (.getMessage t)))))
                    (try (start-stream! account)
                         (catch Throwable t
                           (log-message! (str "stream failed to start: "
                                              (.getMessage t))))))
                (log-message!
                  "connected but no managedAccounts event arrived within 5s — type /refresh once it shows up")))))
      (catch Throwable t
        (st/set-tws-status! :disconnected)
        (log-message! (str "connect thread died: " (.getMessage t)))))))

(defn disconnect! []
  (stop-stream!)
  (stop-health-watchdog!)
  (try (ibkr/disconnect!) (catch Throwable _))
  (reset! conn-atom nil)
  (st/set-tws-status! :disconnected)
  (log-message! "disconnected"))

(defn connected? []
  (some? @conn-atom))

(defn live-conn
  "Return the active IB client, or nil if not connected. Public accessor so
   one-shot queries (option quotes, ad-hoc snapshots) don't need to reach
   into the private conn-atom."
  []
  @conn-atom)
