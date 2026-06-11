(ns options-trader.tui.main
  "Charm.clj-based terminal UI with custom event loop for streaming output."
  (:require [cheshire.core :as json]
            [charm.input.handler :as input]
            [charm.input.keymap :as km]
            [charm.render.core :as render]
            [charm.terminal :as term]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.data.ibkr :as ibkr]
            [options-trader.db.queries.portfolio :as qp]
            [options-trader.paths :as paths]
            [options-trader.portfolio.core :as portfolio]
            [options-trader.tui.conversation :as conv]
            [options-trader.tui.llm :as llm]
            [options-trader.tui.pi-proc :as pi-proc]
            [options-trader.tui.proc :as proc]
            [options-trader.tui.runtime-context :as rt-ctx]
            [options-trader.data.market-data :as md]
            [options-trader.data.quotes :as quotes]
            [options-trader.tui.ibkr :as tui-ibkr]
            [options-trader.tui.render :as render-ui]
            [options-trader.tui.slash :as opts-slash]
            [options-trader.tui.state :as st]
            [options-trader.tui.watchlist :as watchlist]
            [options-trader.util :as util]
            [options-trader.indicators.engine :as indicators]
            [options-trader.screener.registry :as screener]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as log-appenders])
  (:import [org.jline.terminal Terminal]
           [org.jline.utils Signals]))

;; Dropping buffer of 1: multiple refresh signals coalesce into a single
;; "render at most once" cue. Without this, the blocking `>!!` writers used
;; by the event handlers back-pressured the stream-reader thread under heavy
;; streaming (50+ events/sec from pi/claude) → TUI froze once chat filled.
(defonce ^:private refresh-chan (a/chan (a/dropping-buffer 1)))
(defonce ^:private ds-atom (atom nil))
;; Holds the currently-spawned agent Process so Escape can kill it. Reset
;; before each spawn and cleared in the spawn's finally block. Single in-flight
;; request invariant — the TUI rejects new sends while :streaming? is true.
(defonce ^:private running-proc-atom (atom nil))
(def state-width (atom 80))
(def state-height (atom 24))

(defn port->mode
  "Classify a TWS/Gateway port. 7497/4002 = paper, 7496/4001 = live. The TUI
   surfaces this so 'oh I thought I was on prod' surprises don't happen."
  [port]
  (case (int (or port 0))
    7497 :paper-tws
    4002 :paper-gateway
    7496 :live-tws
    4001 :live-gateway
    :unknown))

(defn- mode-banner [profile ibkr-config]
  (let [{:keys [host port client-id]} ibkr-config
        mode (port->mode port)
        warn? (#{:paper-tws :paper-gateway :unknown} mode)]
    (str (if warn? "⚠  " "✅ ")
         "profile=" (or profile "dev")
         "  ib=" host ":" port
         "  mode=" (name mode)
         "  client-id=" client-id)))

(defn default-account-id [ds]
  (or (ibkr/default-account)
      (qp/default-account-from-positions ds)
      (qp/default-account-from-summary   ds)))

(defn refresh-portfolio! [ds]
  (let [account-id (or (:account-id @st/state)
                       (default-account-id ds))
        store      (portfolio/->JdbcStore ds)
        positions  (when account-id (portfolio/read-positions store account-id))
        summary    (when account-id (portfolio/read-account-summary store account-id))]
    (st/set-portfolio! {:positions       positions
                         :account-summary summary
                         :account-id      account-id})))

(defn parse-event [line]
  (util/safe-json-parse line))

(defn text-blocks [content]
  (->> content (filter #(= "text" (:type %))) (map :text)))

(defn tool-use-blocks [content]
  (->> content (filter #(= "tool_use" (:type %)))))

(defn thinking-blocks [content]
  (->> content (filter #(= "thinking" (:type %))) (map #(or (:thinking %) (:text %)))))

(defn tool-summary [tu]
  (let [in (:input tu)
        hint (some #(get in %) [:symbol :name :query :sql :path :file_path :command])]
    (str (:name tu) (when hint (str " " (subs (str hint) 0 (min 40 (count (str hint)))))))))


(defn handle-event [ev sid-atom]
  (let [t (:type ev)
        msg-ev (:message ev)
        content (:content msg-ev)]
    (when-let [sid (:session_id ev)]
      (reset! sid-atom sid))
    (swap! st/state assoc :scroll-offset 0)
    (case t
      "assistant"
      (do
        ;; Thinking stays out of chat (would flood). Tool calls go INTO chat
        ;; so silent tool failures aren't invisible — that was the actual
        ;; bug behind "agent stops mid-response with no output".
        (doseq [th (thinking-blocks content)]
          (st/append-activity! :thinking th)
          (a/>!! refresh-chan :refresh))
        (doseq [tu (tool-use-blocks content)]
          (st/append-chat! :tool (tool-summary tu))
          (a/>!! refresh-chan :refresh))
        (doseq [t (text-blocks content)]
          (st/append-message! :assistant t)
          (a/>!! refresh-chan :refresh)))
      "result"
      (when (:is_error ev)
        (st/append-chat! :system (str "claude error: " (:error ev "unknown")))
        (a/>!! refresh-chan :refresh))
      nil)))


(defn handle-pi-event [ev]
  (swap! st/state assoc :scroll-offset 0)
  (case (:type ev)
    :text
    (do (st/append-to-last-assistant! (:text ev))
        (a/>!! refresh-chan :refresh))

    :thinking
    (do (st/append-activity! :thinking (:text ev))
        (a/>!! refresh-chan :refresh))

    :tool
    ;; Tool starts + ends BOTH go to chat as :tool so the user can see what
    ;; the agent attempted and whether each call succeeded. Errors used to
    ;; route to :activity (invisible) and the agent would silently stall
    ;; after one tool failed.
    (let [label (if (:start? ev)
                  (str "🔧 " (:name ev) " "
                       (when-let [a (:args ev)]
                         (let [s (pr-str a)]
                           (if (> (count s) 60) (str (subs s 0 60) "…") s))))
                  (str (if (:error? ev) "✗ " "✓ ") (:name ev)))]
      (st/append-chat! :tool label)
      (a/>!! refresh-chan :refresh))

    :usage
    (let [scope (:scope @st/state)
          usage {:input-tokens  (or (:input-tokens ev) 0)
                 :output-tokens (or (:output-tokens ev) 0)}]
      (conv/record-turn-stats! scope usage))

    nil))


(defn- start-streaming-proc!
  "Start a streaming agent subprocess (claude / pi). stderr stays separate
   so the line-handler reading stdout doesn't get garbled by warnings."
  ^Process [cmd cwd env]
  (proc/spawn! {:cmd cmd :cwd cwd :env env :merge-err? false}))

(defn- drain-stderr-async!
  "Read proc's stderr in a background daemon thread and surface each line in
   the chat as a :system message. Do NOT use timbre/println — those write to
   stdout, which is the same stream JLine is drawing the TUI on, so the log
   line would land directly on top of the rendered screen."
  [^Process proc tag]
  (let [thread
        (Thread.
         (fn []
           (try
             (with-open [reader (java.io.BufferedReader.
                                  (java.io.InputStreamReader. (.getErrorStream proc)))]
               (loop []
                 (when-let [line (.readLine reader)]
                   (try
                     (st/append-chat! :system (str tag " stderr: " line))
                     (a/>!! refresh-chan :refresh)
                     (catch Throwable _))
                   (recur))))
             (catch Throwable _))))]
    (.setDaemon thread true)
    (.start thread)
    thread))

(defn- write-user-msg! [^Process proc msg]
  (with-open [writer (java.io.PrintWriter. (.getOutputStream proc) true)]
    (.println writer msg)))

(defn- read-stream-lines!
  "Read lines from a process, calling line-handler for each.
   Returns after the stream ends. Handles cleanup."
  [^Process proc line-handler]
  (with-open [reader (java.io.BufferedReader.
                        (java.io.InputStreamReader. (.getInputStream proc)))]
    (loop []
      (when-let [line (.readLine reader)]
        (line-handler line)
        (recur))))
  (.waitFor proc))

(defn- skill-dirs
  "Return absolute paths of every seeded skill subdir under
   <runtime-claude>/skills/. Used to pass --skill <path> to pi (claude
   auto-discovers via CLAUDE_CONFIG_DIR and doesn't need explicit flags)."
  []
  (let [root (io/file (paths/runtime-claude-skills-dir))]
    (when (.isDirectory root)
      (->> (.listFiles root)
           (filter #(.isDirectory ^java.io.File %))
           (mapv #(.getAbsolutePath ^java.io.File %))))))

(defn- spawn-pi-agent!
  "Spawn and monitor the pi agent subprocess. Resumes the prior pi session
   for `scope` (or generates+records a new one on first use) so each scope
   stays a continuous chat across messages and TUI restarts. Passes every
   seeded skill dir via --skill so pi sees the same skills as claude."
  [model provider system-prompt additional cwd scope user-msg]
  (let [sid       (conv/ensure-pi-session-id! scope)
        all-skill (vec (concat additional (skill-dirs)))
        spec      (pi-proc/spawn-pi {:model model :provider provider
                                     :system-prompt system-prompt
                                     :additional-dirs all-skill
                                     :session-id sid
                                     :cwd cwd})
        proc      (start-streaming-proc! (:cmd spec) cwd nil)]
    (reset! running-proc-atom proc)
    (drain-stderr-async! proc "pi")
    (try
      (write-user-msg! proc user-msg)
      (read-stream-lines! proc
        (fn [line]
          (when-let [ev (pi-proc/parse-event line)]
            (handle-pi-event ev))))
      (let [rc (.waitFor proc)]
        (when (not (zero? rc))
          (st/append-chat! :system
            (str "pi exited with code " rc " — see activity for stderr"))))
      (finally
        (.destroyForcibly proc)
        (reset! running-proc-atom nil)))))

(defn- spawn-claude-agent!
  "Spawn and monitor the claude subprocess. Returns nil on error, truthy on success."
  [model provider system-prompt additional cwd scope user-msg]
  (let [prior       (conv/current-claude-session scope)
        claude-args (cond-> ["--print" "--output-format" "stream-json"
                             "--verbose" "--dangerously-skip-permissions"]
                      system-prompt (conj "--system-prompt" system-prompt)
                      true          (into (mapcat #(vector "--add-dir" %) additional))
                      (:claude-session-id prior)
                      (conj "--resume" (:claude-session-id prior)))
        {:keys [cmd env]} (llm/streaming-invocation
                            {:model model :provider provider :claude-args claude-args})
        proc (start-streaming-proc! cmd cwd env)
        sid  (atom nil)]
    (reset! running-proc-atom proc)
    (drain-stderr-async! proc "claude")
    (try
      (write-user-msg! proc user-msg)
      (read-stream-lines! proc
        (fn [line]
          (when-let [ev (parse-event line)]
            (handle-event ev sid))))
      (let [rc (.waitFor proc)]
        (when (not (zero? rc))
          (st/append-chat! :system
            (str "claude exited with code " rc " — see activity for stderr"))))
      (finally
        (reset! running-proc-atom nil)))
    (when-let [s @sid]
      (conv/record-claude-session! scope s))))

(defn cancel-running-agent!
  "Forcibly terminate the currently-spawned agent subprocess (if any) and
   surface a system message. The spawn thread's finally block clears
   :streaming? and running-proc-atom once the process exits."
  []
  (when-let [^Process proc @running-proc-atom]
    (try (.destroyForcibly proc) (catch Throwable _))
    (st/append-chat! :system "request cancelled")
    (a/>!! refresh-chan :refresh)
    true))

(declare maybe-autocompact!)

(defn spawn-agent! [user-msg]
  (let [agent         (:agent @st/state :claude)
        model         (:model @st/state)
        provider      (:provider @st/state :claude)
        scope         (:scope @st/state)
        cwd           (System/getProperty "user.dir")
        additional    (:additional-dirs @st/state [])
        system-prompt (rt-ctx/build {})
        ;; Order matters: auto-compact may queue a prefix that take-
        ;; prefix-message! consumes on this same turn.
        _             (maybe-autocompact! scope)
        prefix        (st/take-prefix-message!)
        user-msg      (cond->> user-msg
                        prefix (str prefix "\n\n"))]
    (st/set-streaming! true)
    (st/append-message! :user user-msg)
    (a/>!! refresh-chan :refresh)
    (a/thread
      (try
        (case agent
          :pi     (spawn-pi-agent! model provider system-prompt additional cwd scope user-msg)
          :claude (spawn-claude-agent! model provider system-prompt additional cwd scope user-msg))
        (catch Throwable t
          (st/append-chat! :system (str "agent error: " (.getMessage t)))
          (a/>!! refresh-chan :refresh))
        (finally
          (st/set-streaming! false)
          (a/>!! refresh-chan :refresh))))))

(defn handle-quit [_args _ds]
  (st/quit!))

(defn handle-refresh [_args ds]
  (try
    (let [connected? (tui-ibkr/connected?)
          ;; When connected, ALWAYS use the live handshake account, not state.
          ;; Stale state could point at a paper account from a previous run.
          acct (if connected?
                 (or (ibkr/default-account) (:account-id @st/state))
                 (or (:account-id @st/state) (default-account-id ds)))
          ibkr-cfg (:ibkr-config @st/state)
          mode (when ibkr-cfg (port->mode (:port ibkr-cfg)))]
      (cond
        (not acct)
        (st/append-chat! :system
          "no account id known — /connect to TWS first, or run `clojure -M:cli refresh-portfolio`")

        connected?
        (do (st/append-chat! :system
              (str "refreshing portfolio for " acct
                   "  (ib=" (:host ibkr-cfg) ":" (:port ibkr-cfg)
                   "  mode=" (name (or mode :unknown)) ")"))
            (a/thread
              (try
                (tui-ibkr/refresh-from-ibkr! ds acct)
                (let [n (count (:positions @st/state))]
                  (st/append-chat! :system
                    (str "portfolio refreshed from IB — " n " positions  (account=" acct ")")))
                (a/>!! refresh-chan :refresh)
                (catch Throwable t
                  (st/append-chat! :system (str "refresh failed: " (.getMessage t)))
                  (a/>!! refresh-chan :refresh)))))

        :else
        (do (refresh-portfolio! ds)
            (st/append-chat! :system
              (str "TWS not connected — reloaded from DB ("
                   (count (:positions @st/state)) " positions, account=" acct "). "
                   "Use /connect to pull live.")))))
    (catch Throwable t
      (st/append-chat! :system (str "refresh failed: " (.getMessage t))))))

(defn handle-reload-screens [_args ds]
  (try
    (screener/load-screens-from-dir! ds)
    (st/append-chat! :system (str "screens reloaded from " (paths/screens-dir)))
    (catch Throwable t
      (st/append-chat! :system (str "screen reload failed: " (.getMessage t))))))

(defn handle-reload-indicators [_args _ds]
  (try
    (let [cfg   (indicators/load-config)
          inds  (count (:indicators cfg))
          comps (count (:composites cfg))]
      (st/append-chat! :system
        (format "indicators.edn OK at %s — %d base, %d composites. Run /refresh-* to add columns + backfill."
                (paths/indicators-file) inds comps)))
    (catch Throwable t
      (st/append-chat! :system (str "indicators reload failed: " (.getMessage t))))))

(defn- set-provider-model!
  "Apply [provider model] to state respecting agent semantics:
   - For :claude agent, provider routes through llm/set-provider! which whitelists
     the Anthropic-compat endpoints (claude/ollama/kimi/minimax).
   - For :pi agent, pi has its own provider universe (moonshotai/openai/etc.) that
     the claude-side whitelist doesn't know about — just record state and let
     pi parse it at spawn time.
   Persists to <config-root>/tui-prefs.edn so the next launch picks the same combo."
  [provider model]
  (let [agent (:agent @st/state :claude)]
    (when (and (= :claude agent) provider)
      (llm/set-provider! provider))
    (when model (llm/set-model! model))
    (swap! st/state
           (fn [s]
             (cond-> s
               provider (assoc :provider provider)
               model    (assoc :model model))))
    (st/persist-tui-prefs!)))

(defn- parse-provider-model
  "Parse 'provider/model' or 'provider:model' or bare 'model'. Returns [prov mdl]
   where prov may be nil (meaning 'no change to provider')."
  [s]
  (cond
    (str/includes? s "/")
    (let [[p m] (str/split s #"/" 2)] [(keyword p) m])
    (str/includes? s ":")
    (let [[p m] (str/split s #":" 2)] [(keyword p) m])
    :else
    [nil s]))

(defn handle-model [args _ds]
  (if-let [model-arg (not-empty (str/join " " args))]
    (let [[provider model] (parse-provider-model model-arg)]
      (try
        (set-provider-model! provider model)
        (st/append-chat! :system
          (str "model set to "
               (name (:provider @st/state :claude)) ":" (:model @st/state)
               "  (agent: " (name (:agent @st/state :claude)) ")"))
        (catch Exception e
          (st/append-chat! :system (str "bad /model: " (.getMessage e))))))
    (st/append-chat! :system
      (str "provider: " (name (:provider @st/state :claude))
           "  model: "  (:model @st/state)
           "  (agent: " (name (:agent @st/state :claude)) ")"))))

(defn handle-agent [args _ds]
  (if-let [full-arg (not-empty (str/join " " args))]
    (let [parts     (str/split full-arg #"\s+")
          [a-str model-part] parts
          agent-key (some-> a-str str/trim str/lower-case keyword)]
      (when-not (contains? #{:claude :pi} agent-key)
        (st/append-chat! :system (str "unknown agent: " a-str " — use /agent pi or /agent claude"))
        (st/append-chat! :system
          (str "agent: " (name (:agent @st/state :claude))
               "  provider: " (name (:provider @st/state :claude))
               "  model: " (:model @st/state))))
      (when (contains? #{:claude :pi} agent-key)
        (try
          (llm/set-agent! agent-key)
          (swap! st/state assoc :agent agent-key)
          (st/persist-tui-prefs!)
          (st/append-chat! :system (str "agent set to " (name agent-key)))
          (catch Exception e
            (st/append-chat! :system (str "bad /agent: " (.getMessage e)))))
        (when (second parts)
          (let [mp (second parts)
                [prov mdl] (parse-provider-model mp)]
            (try
              (set-provider-model! prov mdl)
              (st/append-chat! :system
                (str "provider=" (name (:provider @st/state :claude))
                     "  model="  (:model @st/state)))
              (catch Exception e
                (st/append-chat! :system
                  (str "couldn't set model from " mp ": " (.getMessage e))))))))))
  (st/append-chat! :system
    (str "agent: " (name (:agent @st/state :claude))
         "  provider: " (name (:provider @st/state :claude))
         "  model: " (:model @st/state))))

(defn handle-connect [_args ds]
  (if-let [cfg (:ibkr-config @st/state)]
    (do
      (st/append-chat! :system
        (str "connecting → " (:host cfg) ":" (:port cfg)
             " (" (name (port->mode (:port cfg))) ")"))
      (tui-ibkr/connect-async! (assoc cfg :ds ds)))
    (st/append-chat! :system
      "no ibkr config in state — relaunch the TUI with --profile prod or :dev")))

(defn handle-disconnect [_args _ds]
  (tui-ibkr/disconnect!))

(defn- fmt-num [v dec]
  (if (number? v) (format (str "%." dec "f") (double v)) "—"))

(defn handle-quote
  "Live detailed quote for a stock symbol.
   Usage:   /quote <SYMBOL>
   Example: /quote MPWR"
  [args _ds]
  (cond
    (not (tui-ibkr/connected?))
    (st/append-chat! :system "no TWS connection — /connect first")

    (empty? args)
    (st/append-chat! :system "usage: /quote <SYMBOL>")

    :else
    (let [sym (str/upper-case (str (first args)))]
      (st/append-chat! :system (str "fetching " sym " ..."))
      (a/thread
        (try
          (let [t0   (System/currentTimeMillis)
                conn (tui-ibkr/live-conn)
                src  (md/make-source {:type :ibkr :ib-client conn})
                q    (quotes/detailed-quote src @ds-atom sym)
                dt   (- (System/currentTimeMillis) t0)]
            (cond
              (= :unavailable q)
              (st/append-chat! :system
                (str sym ": TWS rejected the snapshot (" dt "ms)"))

              (= :timeout q)
              (st/append-chat! :system
                (str sym ": snapshot timed out after " dt "ms — "
                     "could be no MD subscription, no IB slot free, or TWS not pushing"))

              :else
              (do
                (st/append-chat! :system
                  (str sym " [" dt "ms]"
                       "  bid=" (fmt-num (:bid q) 2)
                       " ask=" (fmt-num (:ask q) 2)
                       " last=" (fmt-num (:last q) 2)
                       " open=" (fmt-num (:open q) 2)
                       " high=" (fmt-num (:high q) 2)
                       " low=" (fmt-num (:low q) 2)
                       "  mode=" (name (or (:data-mode q) :unknown))))
                (st/append-chat! :system
                  (str "volume: day=" (some-> (:day-volume q) long)
                       " vwap=" (fmt-num (:day-vwap q) 2)
                       " avg14d=" (some-> (:avg-volume-14d q) long)
                       "  ratio=" (fmt-num (:volume-ratio q) 2)))
                (st/append-chat! :system
                  (str "put/call:  oi-ratio=" (fmt-num (:pc-oi-ratio q) 3)
                       "  vol-ratio=" (fmt-num (:pc-vol-ratio q) 3))))))
          (catch Throwable t
            (st/append-chat! :system
              (str sym ": quote failed — " (.getMessage t)))))))))

(defn handle-option-quote
  "Live snapshot of a single option contract.
   Usage:   /option-quote <SYMBOL> <YYYYMMDD> <STRIKE> <C|P>
   Example: /option-quote MPWR 20260619 800 C"
  [args _ds]
  (cond
    (not (tui-ibkr/connected?))
    (st/append-chat! :system
      "no TWS connection — /connect first")

    (< (count args) 4)
    (st/append-chat! :system
      "usage: /option-quote <SYMBOL> <YYYYMMDD> <STRIKE> <C|P>")

    :else
    (let [[sym expiry strike-s right] args
          strike (try (Double/parseDouble strike-s) (catch Throwable _ nil))
          right  (str/upper-case (str right))]
      (if (or (not strike) (not (#{"C" "P"} right)))
        (st/append-chat! :system "bad args — strike must be numeric, right must be C or P")
        (do
          (st/append-chat! :system
            (str "fetching " sym " " expiry " " strike-s " " right " ..."))
          (a/thread
            (let [conn   (tui-ibkr/live-conn)
                  src    (md/make-source {:type :ibkr :ib-client conn})
                  opts   {:symbol sym :expiry expiry :strike strike :right right}
                  ;; Snapshot-mode reliably returns prev-session close + bid/ask
                  ;; for inactive strikes — TWS sends cached state and a
                  ;; tick-snapshot-end terminator, no waiting on live ticks.
                  q0     (quotes/option-quote-snapshot src opts)
                  ;; Snapshot rarely carries model greeks after-hours (no live
                  ;; computation). Hand TWS the close prices and let it back
                  ;; out IV + greeks — works any time of day.
                  need-calc? (and (map? q0)
                                  (nil? (:iv q0))
                                  (nil? (:delta q0)))
                  q      (if need-calc?
                           (let [_ (st/append-chat! :system
                                     "no live greeks — falling back to reqCalcImpliedVolatility")
                                 calc (quotes/calc-option-greeks src opts)]
                             (if (map? calc) (merge q0 calc) q0))
                           q0)]
              (cond
                (= :unavailable q)
                (st/append-chat! :system "option quote unavailable (TWS rejected)")

                (= :timeout q)
                (st/append-chat! :system "option quote timed out — TWS sent no snapshot end")

                :else
                (do
                  (st/append-chat! :system
                    (str sym " " expiry " " (fmt-num strike 0) " " right
                         "  ──  bid=" (fmt-num (:bid q) 2)
                         " ask="     (fmt-num (:ask q) 2)
                         " last="    (fmt-num (:last q) 2)
                         " close="   (fmt-num (:close q) 2)
                         " vol="     (or (some-> (:volume q) long) "—")
                         "  mode="   (name (or (:data-mode q) :unknown))))
                  (st/append-chat! :system
                    (str "greeks (model):  IV=" (fmt-num (:iv q) 4)
                         " Δ=" (fmt-num (:delta q) 4)
                         " Γ=" (fmt-num (:gamma q) 4)
                         " Θ=" (fmt-num (:theta q) 4)
                         " ν=" (fmt-num (:vega q) 4)
                         (when-let [u (:underlying-price q)]
                           (str "  underlying=" (fmt-num u 2)))))
                  (when (and (nil? (:bid q)) (nil? (:ask q)) (nil? (:last q)))
                    (st/append-chat! :system
                      "no live bid/ask/last — market closed or no orders on this strike; :close shown above is the previous-session close"))
                  (doseq [w (:warnings q)]
                    (st/append-chat! :system
                      (str "warning " (:code w) ": " (:message w))))
                  ;; Errors from TWS now surface — previously they were
                  ;; silently dropped and you got a row of dashes.
                  (doseq [e (:errors q)]
                    (st/append-chat! :system
                      (str "error " (:code e) ": " (:message e)))))))))))))

(defn handle-stream-stats [_args _ds]
  (let [s    @st/state
        c    (get-in s [:stream :counters])
        n-p  (count (:positions s))
        now  (System/currentTimeMillis)
        ago  (fn [t] (when t (str (long (/ (- now t) 1000)) "s ago")))]
    (st/append-chat! :system
      (str "counters: portfolio=" (:update-portfolio c)
           "  account-value=" (:update-account-value c)
           "  pnl=" (:pnl c)
           "  pnl-single=" (:pnl-single c)
           "  account-time=" (:update-account-time c)
           "  download-end=" (:account-download-end c)
           "  other=" (:other c)))
    (st/append-chat! :system
      (str "state: positions=" n-p
           "  tws=" (name (:tws-status s :disconnected))
           "  account=" (or (:account-id s) "nil")
           "  pnl-single-subs=" (count (get-in s [:stream :pnl-single-rids]))
           "  portfolio-updated=" (ago (:portfolio-updated-at s))
           "  last-pnl=" (ago (:last-pnl-at c))))))

(defn handle-clear-investigation [_args _ds]
  (swap! st/state assoc :scope :scratch)
  (st/append-chat! :system "scope = scratch"))

(defn handle-add-to-watchlist [args ds]
  (let [sym (opts-slash/normalise-symbol (first args))]
    (if-not sym
      (st/append-chat! :system "usage: /add-to-watchlist <SYMBOL>")
      (let [conn (tui-ibkr/current-conn)
            res  (watchlist/add! {:ds ds :conn conn :sym sym})]
        (case (or (:error res) :ok)
          :ok
          (do (watchlist/persist!)
              (st/append-chat! :system (str "watching " (:sym res))))

          :already-watching
          (st/append-chat! :system (str (:sym res) " is already on the watchlist"))

          :cap-reached
          (st/append-chat! :system
            (str "watchlist is full (max " (:max res) " symbols) — "
                 "remove one with /remove-from-watchlist first"))

          :not-connected
          (st/append-chat! :system "not connected to TWS — /connect first")

          :bad-symbol
          (st/append-chat! :system "usage: /add-to-watchlist <SYMBOL>")

          (st/append-chat! :system (str "watchlist error: " (pr-str res))))))))

(defn handle-remove-from-watchlist [args _ds]
  (let [sym (opts-slash/normalise-symbol (first args))]
    (if-not sym
      (st/append-chat! :system "usage: /remove-from-watchlist <SYMBOL>")
      (let [res (watchlist/remove! {:conn (tui-ibkr/current-conn) :sym sym})]
        (if (:error res)
          (st/append-chat! :system (str sym " is not on the watchlist"))
          (do (watchlist/persist!)
              (st/append-chat! :system (str "unwatched " (:sym res)))))))))

(defn handle-watchlist [_args _ds]
  (let [wl (:watchlist @st/state)]
    (if (empty? wl)
      (st/append-chat! :system "watchlist is empty — /add-to-watchlist <SYM>")
      (st/append-chat! :system (str "watching: " (clojure.string/join " " wl))))))

(defn handle-reset [_args _ds]
  (let [scope (:scope @st/state)]
    (conv/clear-claude-session! scope)
    (conv/clear-pi-session! scope)
    (st/append-chat! :system "claude + pi sessions reset for current scope")))

(def ^:private compact-prompt
  (str "Produce a concise (≤200 words) summary of the conversation so far. "
       "PRESERVE: symbols/topics analysed, open questions, user preferences, "
       "any decisions reached. DROP: specific numeric values, full filing "
       "excerpts, verdict-card formatting, repeated tool output. The summary "
       "becomes background context for a fresh session, so write it as a "
       "briefing TO yourself, not as prose."))

(def ^:dynamic *auto-compact-threshold*
  "Auto-compact when scope's cumulative input-tokens exceed this.
   100k ≈ 50% of a 200k window. Overridable via --compact-threshold N
   on the launcher, which alter-var-roots this at startup."
  100000)

(defn set-auto-compact-threshold!
  "Set the auto-compact token threshold for the running session. The
   var is dynamic for tests/REPL overrides; alter-var-root so the change
   is visible in the async event-loop threads that drive maybe-autocompact!."
  [n]
  (alter-var-root #'*auto-compact-threshold* (constantly (long n))))

(defn- scope-input-tokens [scope]
  (or (-> (conv/current-claude-session scope) :tokens-input) 0))

(defn- do-compact!
  "Summarise via llm/complete, drop sessions, queue the summary as the
   next-msg prefix. Returns the summary or nil on llm failure."
  [scope]
  (try
    (let [summary (llm/complete {} compact-prompt)]
      (conv/clear-claude-session! scope)
      (conv/clear-pi-session! scope)
      (st/queue-prefix-message!
        (str "Compacted-session briefing from prior turns:\n\n" summary))
      summary)
    (catch Throwable t
      (log/error t "compact failed" {:scope scope :ex-data (ex-data t)})
      nil)))

(defn handle-compact [_args _ds]
  (let [scope (:scope @st/state)]
    (a/thread
      (st/append-chat! :system "compacting — asking agent to summarise current context...")
      (a/>!! refresh-chan :refresh)
      (if-let [summary (do-compact! scope)]
        (st/append-chat! :system
          (str "session reset; " (count summary) "-char summary queued "
               "as prefix for your next message"))
        (st/append-chat! :system "/compact failed — see logs"))
      (a/>!! refresh-chan :refresh))))

(defn- maybe-autocompact! [scope]
  (let [tokens *auto-compact-threshold*
        used   (scope-input-tokens scope)]
    (when (> used tokens)
      (st/append-chat! :system
        (format "auto-compact: scope tokens %d > threshold %d — summarising..."
                used tokens))
      (a/>!! refresh-chan :refresh)
      (if-let [summary (do-compact! scope)]
        (st/append-chat! :system
          (format "auto-compact done; %d-char summary queued" (count summary)))
        (st/append-chat! :system
          "auto-compact failed; sending your message without compaction"))
      (a/>!! refresh-chan :refresh))))

(defonce ^:private last-sessions-list (atom []))

(defn- fmt-when [^long mtime]
  (let [zdt (.. (java.time.Instant/ofEpochMilli mtime)
                (atZone (java.time.ZoneId/systemDefault)))]
    (.format zdt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))))

(defn- fmt-session-row [i {:keys [agent scope-key preview mtime]}]
  (format "%3d  %-6s %-10s %-16s  %s"
          i (name agent)
          (if scope-key (name scope-key) "(orphan)")
          (fmt-when mtime)
          (or preview "(no preview)")))

(defn- resume-session-by-index [n]
  (let [rows @last-sessions-list]
    (cond
      (empty? rows)
      (st/append-chat! :system "no listing yet — run /sessions first")

      (or (nil? n) (< n 1) (> n (count rows)))
      (st/append-chat! :system (format "usage: /sessions resume <1-%d>" (count rows)))

      :else
      (let [{:keys [agent session-id scope-key file]} (nth rows (dec n))
            target-scope (or scope-key :scratch)
            turns        (conv/replay-turns agent file)]
        (case agent
          :claude (conv/record-claude-session! target-scope session-id)
          :pi     (conv/record-pi-session!     target-scope session-id))
        (swap! st/state assoc :scope target-scope :agent agent)
        (try (conv/persist-claude-sessions!) (catch Throwable _ nil))
        (st/append-chat! :system
          (format "── resuming %s session %s (scope: %s) — %d turns ──"
                  (name agent)
                  (subs session-id 0 (min 8 (count session-id)))
                  (name target-scope)
                  (count turns)))
        (doseq [{:keys [role text]} turns]
          (st/append-chat! role text))
        (st/append-chat! :system
          "── end of replay — type a message to continue ──")))))

(defn handle-sessions [args _ds]
  (let [subcmd (some-> (first args) str/lower-case)]
    (cond
      (or (nil? subcmd) (= "list" subcmd))
      (let [rows (conv/list-all-transcripts
                   {:runtime-claude-dir (paths/runtime-claude-dir)
                    :pi-session-dir     (paths/pi-session-dir)})]
        (if (empty? rows)
          (st/append-chat! :system "no transcripts on disk")
          (do
            (reset! last-sessions-list rows)
            (st/append-chat! :system
              "  #  agent   scope      when              preview")
            (doseq [[i r] (map-indexed vector rows)]
              (st/append-chat! :system (fmt-session-row (inc i) r)))
            (st/append-chat! :system
              "  /sessions resume <n> to pick one"))))

      (= "resume" subcmd)
      (let [n (try (Long/parseLong (str (second args)))
                   (catch Throwable _ nil))]
        (resume-session-by-index n))

      :else
      (st/append-chat! :system "usage: /sessions | /sessions resume <n>"))))

(defn handle-help [_args _ds]
  (st/append-chat! :system
    (str/join "\n"
      ["TUI-local commands (don't touch the agent):"
       "  /quit | /exit | /q              exit the TUI"
       "  /refresh                        re-read positions from DB"
       "  /reload-screens                 rescan <config>/screens/ into DB"
       "  /reload-indicators              validate <config>/indicators.edn"
       "  /connect | /disconnect          TWS connection"
       "  /model [provider:id]            show or switch model"
       "  /agent [pi|claude]              show or switch agent"
       "  /clear-investigation            return to :scratch scope"
       "  /reset                          drop claude+pi sessions for current scope"
       "  /compact                        summarise + reset sessions, queue summary as next-msg prefix"
       "                                  (auto-triggers when scope tokens exceed 100k)"
       "  /sessions                       list on-disk transcripts (both agents)"
       "  /sessions resume <n>            resume the n-th transcript from /sessions"
       "  /add-to-watchlist <SYM>         stream live quotes for SYM in the right pane"
       "  /remove-from-watchlist <SYM>    drop SYM from the watchlist"
       "  /watchlist                      list current watchlist symbols"
       ""
       "Hybrid (TUI side-effect + tells the agent):"
       "  /investigate <SYM>              focus scope on SYM + ask agent for overview"
       ""
       "Agent-routed (forwarded to claude/pi, uses MCP tools):"
       "  /portfolio                      → portfolio_summary"
       "  /screens                        → list_screens"
       "  /run <screen> [args]            → run_screen"
       ""
       "Anything else you type is sent to the agent directly."])))

(def ^:private command-table
  "TUI-local commands. Mutate the TUI process state directly; never reach the
   agent. Only put a command here when there's no MCP-tool equivalent (or the
   side effect is purely TUI-local — model switching, scope state, quit, …)."
  {"/quit"                handle-quit
   "/exit"                handle-quit
   "/q"                   handle-quit
   "/refresh"             handle-refresh
   "/refresh-portfolio"   handle-refresh
   "/refresh-positions"   handle-refresh
   "/stream-stats"        handle-stream-stats
   "/reload-screens"      handle-reload-screens
   "/reload-indicators"   handle-reload-indicators
   "/connect"             handle-connect
   "/disconnect"          handle-disconnect
   "/quote"               handle-quote
   "/dq"                  handle-quote
   "/option-quote"        handle-option-quote
   "/oq"                  handle-option-quote
   "/model"               handle-model
   "/agent"               handle-agent
   "/clear-investigation" handle-clear-investigation
   "/reset"               handle-reset
   "/compact"             handle-compact
   "/sessions"            handle-sessions
   "/add-to-watchlist"      handle-add-to-watchlist
   "/remove-from-watchlist" handle-remove-from-watchlist
   "/watchlist"             handle-watchlist
   "/help"                handle-help})

(def ^:private hybrid-commands
  "Slash commands that BOTH mutate TUI state AND forward an explicit prompt
   to the agent. The handler returns the prompt string (or nil to skip the
   agent leg). Currently just /investigate — set scope locally so future
   replies are scoped, then ask the agent for an overview using MCP tools."
  {"/investigate"
   (fn [args]
     (if-let [sym (opts-slash/normalise-symbol (first args))]
       (do (swap! st/state assoc :scope (keyword sym))
           (st/append-chat! :system (str "scope = " sym))
           (str "I want to investigate " sym ". Give me a quick overview — "
                "call portfolio_summary to see if I hold it, then use the "
                "fetch_news, fetch_filings and get_indicators tools to surface "
                "anything notable. Keep it brief."))
       (do (st/append-chat! :system "usage: /investigate <SYMBOL>") nil)))})

(def ^:private agent-routed
  "Slash commands forwarded to the agent as natural language. The agent has
   MCP tools (portfolio_summary, list_screens, run_screen, etc.) and picks
   the right one based on the prompt + runtime CLAUDE.md context. Each entry
   names the expected MCP tool in the prompt so the agent doesn't have to
   guess."
  {"/portfolio" (fn [_args]
                  "Show me my portfolio summary using the portfolio_summary tool.")
   "/screens"   (fn [_args]
                  "List the screens available using the list_screens tool.")
   "/run"       (fn [args]
                  (if (seq args)
                    (str "Run the '" (first args) "' screen using the run_screen tool"
                         (when (next args) (str " with arguments: " (str/join " " (next args))))
                         ". Show me the matched symbols.")
                    "Which screen should I run? Use list_screens to see what's available."))})

(defn dispatch-slash [line ds]
  (let [parts (str/split (str/trim line) #"\s+")
        cmd   (first parts)
        args  (rest parts)]
    (cond
      (get command-table cmd)
      ((get command-table cmd) args ds)

      (get hybrid-commands cmd)
      (when-let [prompt ((get hybrid-commands cmd) args)]
        (spawn-agent! prompt))

      (get agent-routed cmd)
      (spawn-agent! ((get agent-routed cmd) args))

      ;; Unknown slash → forward the whole line to the agent. Lets claude's
      ;; built-in slash commands (and any sub-agent slash routing it knows
      ;; about) handle anything we haven't enumerated here.
      :else
      (spawn-agent! line))))

(defn on-enter [state ds]
  (let [line (str/trim (:input state))]
    (when-not (str/blank? line)
      (st/push-input-history! line)
      (st/clear-input!)
      (cond
        (str/starts-with? line "/") (dispatch-slash line ds)
        (:streaming? state)        (st/append-chat! :system "still streaming")
        :else                       (spawn-agent! line)))))

(defn on-backspace [state]
  (let [{:keys [input cursor]} state]
    (when (pos? cursor)
      (let [c (int cursor)]
        (swap! st/state assoc
               :input  (str (subs input 0 (dec c)) (subs input c))
               :cursor (dec c))))))

(defn on-char [state ch]
  (let [{:keys [input cursor]} state
        c (int cursor)]
    (swap! st/state assoc
           :input  (str (subs input 0 c) ch (subs input c))
           :cursor (inc c))))

(defn on-left [_state]
  (swap! st/state update :cursor #(max 0 (dec %))))

(defn on-right [state]
  (let [c (:cursor state) n (count (:input state))]
    (swap! st/state assoc :cursor (min n (inc c)))))

(defn on-home [_state]
  (swap! st/state assoc :cursor 0))

(defn on-end [state]
  (swap! st/state assoc :cursor (count (:input state))))

(defn process-mouse
  "Handle mouse events — currently scroll-wheel up/down to scroll the chat.
   Works regardless of focus so you can scroll while typing."
  [event]
  (case (:wheel event)
    :wheel-up   (st/adjust-scroll! -3)
    :wheel-down (st/adjust-scroll! 3)
    nil))

(defn process-key [event]
  (let [state @st/state
        focus (:focus state)
        k     (:key event)
        ctrl  (:ctrl event)]
    (cond
      (and ctrl (= "c" k))
      (do (try (tui-ibkr/disconnect!) (catch Throwable _))
          (st/quit!))

      ;; Esc cancels the in-flight agent request (any focus).
      (= :escape k)
      (cancel-running-agent!)

      ;; PgUp/PgDn route to whichever pane currently has focus. With
      ;; :input or :chat focus they walk the chat; with :portfolio or
      ;; :watchlist focus they walk that table.
      (= :page-up k)
      (case focus
        :portfolio (st/adjust-portfolio-scroll! (- (max 1 (int (* @state-height 0.4)))))
        :watchlist (st/adjust-watchlist-scroll! (- (max 1 (int (* @state-height 0.4)))))
        (st/adjust-scroll! (- @state-height)))

      (= :page-down k)
      (case focus
        :portfolio (st/adjust-portfolio-scroll! (max 1 (int (* @state-height 0.4))))
        :watchlist (st/adjust-watchlist-scroll! (max 1 (int (* @state-height 0.4))))
        (st/adjust-scroll! @state-height))

      ;; Tab cycles focus :input → :chat → :portfolio → :watchlist → :input.
      (= :tab k)
      (do (st/toggle-focus!)
          (swap! st/state assoc :scroll-offset 0))

      ;; --- Chat focus ---
      (= :chat focus)
      (cond
        (= :up k)     (st/adjust-scroll! -1)
        (= :down k)   (st/adjust-scroll! 1)
        (= :page-up k)   (st/adjust-scroll! (- @state-height))
        (= :page-down k) (st/adjust-scroll! @state-height)
        (= :home k)   (swap! st/state assoc :scroll-offset 0)
        (= :end k)    (swap! st/state assoc :scroll-offset 99999)
        ;; Any other key switches to input (only printable chars inserted)
        :else (do (swap! st/state assoc :focus :input :scroll-offset 0)
                  (when (string? k) (on-char (assoc state :focus :input) k))))

      ;; --- Input focus ---
      :else
      (cond
        (= :enter k)      (on-enter state @ds-atom)
        (= :backspace k)  (on-backspace state)
        (= :left k)       (on-left state)
        (= :right k)      (on-right state)
        (= :home k)       (on-home state)
        (= :end k)        (on-end state)
        (= :up k)         (st/recall-prev!)
        (= :down k)       (st/recall-next!)
        k                 (on-char state k)
        :else nil))))

(defn do-render! [renderer]
  (render/render! renderer (render-ui/render @st/state @state-width @state-height)))

(defn start-input-thread! [^Terminal terminal keymap input-chan running?]
  (let [thread (Thread.
                (fn []
                  (while @running?
                    (try
                      (when-let [event (input/read-event terminal
                                                         :timeout-ms 50
                                                         :keymap keymap)]
                        (let [m (cond
                                  (= :mouse (:type event))
                                  (let [raw-button (:button event)
                                        wheel (case (int raw-button)
                                                4 :wheel-up   5 :wheel-down
                                                6 :wheel-left 7 :wheel-right
                                                nil)]
                                    {:type :mouse
                                     :wheel (or wheel (:action event))
                                     :button (if wheel :none
                                                 (case (int raw-button)
                                                   0 :left 1 :middle 2 :right :none))
                                     :x (:x event) :y (:y event)
                                     :ctrl (:ctrl event) :alt (:alt event)
                                     :shift (:shift event)})

                                  (= :focus (:type event))
                                  {:type :focus}

                                  (= :blur (:type event))
                                  {:type :blur}

                                  :else
                                  (let [key (if (= :runes (:type event))
                                              (:runes event)
                                              (:type event))]
                                    {:type :key :key key
                                     :ctrl (:ctrl event)
                                     :alt (:alt event)
                                     :shift (:shift event)}))]
                          (a/>!! input-chan m)))
                      (catch InterruptedException _
                        (reset! running? false))
                      (catch Exception _ nil)))))]
    (.setDaemon thread true)
    (.start thread)
    thread))


(defn- init-tui-state!
  "Initialize state atom and run any startup refresh."
  [{:keys [ds account-id ibkr-config profile initial-message compact-threshold]}]
  (when compact-threshold (set-auto-compact-threshold! compact-threshold))
  (st/reset-state!)
  ;; Any state mutation (stream position upsert, status change, scope switch,
  ;; etc.) triggers a render. Without this watch, background updates from the
  ;; IB stream wouldn't paint until the user pressed a key.
  ;; remove-watch keeps it idempotent across init calls / hot reloads.
  (remove-watch st/state ::refresh-on-change)
  (add-watch st/state ::refresh-on-change
             (fn [_ _ old new]
               (when-not (identical? old new)
                 (a/put! refresh-chan :refresh))))
  (st/load-input-history!)
  (st/load-tui-prefs!)
  ;; Pull the persisted watchlist symbol list into memory immediately so
  ;; the panel renders even before TWS is up. Streams get re-attached the
  ;; first time the connection flips to :connected — see the watch below.
  (swap! st/state assoc :watchlist (watchlist/load-symbols))
  (remove-watch st/state ::watchlist-resubscribe)
  (add-watch st/state ::watchlist-resubscribe
             (fn [_ _ old new]
               (when (and (not= :connected (:tws-status old))
                          (= :connected (:tws-status new))
                          (seq (:watchlist new))
                          (empty? (:watchlist-subs new)))
                 (let [syms (vec (:watchlist new))]
                   ;; Drop the in-memory list so add! doesn't reject as
                   ;; already-watching, then resubscribe each.
                   (swap! st/state assoc :watchlist [])
                   (future
                     (doseq [s syms]
                       (watchlist/add! {:ds   @ds-atom
                                        :conn (tui-ibkr/current-conn)
                                        :sym  s})))))))
  ;; Sync the loaded agent/provider/model into llm's atoms so the first spawn
  ;; reads the persisted choice — state.clj loads into the state atom, but the
  ;; spawn paths also consult llm/active-* atoms for defaults.
  (let [{:keys [agent provider model]} @st/state]
    (try (when agent    (llm/set-agent!    agent))    (catch Throwable _))
    (try (when (and provider (= :claude agent))
           (llm/set-provider! provider))              (catch Throwable _))
    (try (when model    (llm/set-model!    model))    (catch Throwable _)))
  (reset! ds-atom ds)
  (swap! st/state assoc :profile profile :ibkr-config ibkr-config)
  (when account-id (swap! st/state assoc :account-id account-id))
  (when initial-message (st/append-chat! :system initial-message))
  (when ibkr-config
    (st/append-chat! :system (mode-banner profile ibkr-config)))
  (when ds
    (try (refresh-portfolio! ds)
         (catch Throwable t
           (st/append-chat! :system (str "portfolio load skipped: " (.getMessage t))))))
  (when ds
    (try
      (screener/load-screens-from-dir! ds)
      (screener/start-watcher! ds)
      (catch Throwable t
        (st/append-chat! :system (str "screener init failed: " (.getMessage t))))))
  (when (and ds ibkr-config)
    (tui-ibkr/connect-async! (assoc ibkr-config :ds ds))))

(defn- silence-stdout-logging!
  "TUI safety: redirect timbre's :println appender to a file and detach *out*
   from the terminal. Otherwise stray log lines (or println) land directly on
   the JLine-drawn screen — typically right where the input field is."
  []
  (try
    (let [log-file "cache/logs/options-trader.log"]
      (io/make-parents log-file)
      ;; Drop default println appender; keep spit appender to file.
      (log/merge-config!
        {:appenders {:println {:enabled? false}
                     :spit (log-appenders/spit-appender {:fname log-file})}}))
    (catch Throwable _)))

(defn- init-terminal []
  (silence-stdout-logging!)
  (let [terminal     (term/create-terminal)
        original     (term/enter-raw-mode terminal)
        {:keys [width height]} (term/get-size terminal)
        renderer     (render/create-renderer terminal
                                             :fps 60
                                             :alt-screen true
                                             :hide-cursor false)
        keymap       (km/create-keymap terminal)]
    ;; Tell the terminal to send wheel/click events so process-mouse fires.
    ;; Without this charm receives no mouse events at all → scrolling is dead.
    (render/enable-mouse! renderer :normal)
    (reset! state-width width)
    (reset! state-height height)
    {:terminal terminal :original-attrs original
     :width width :height height
     :renderer renderer :keymap keymap}))

(defn- register-signal-handlers! [terminal renderer running? last-size]
  (Signals/register "WINCH"
    (reify Runnable
      (run [_]
        (let [s (term/get-size terminal)]
          (when (or (not= (:width s) (:width @last-size))
                    (not= (:height s) (:height @last-size)))
            (reset! last-size s)
            (reset! state-width (:width s))
            (reset! state-height (:height s))
            (render/update-size! renderer (:width s) (:height s))
            (do-render! renderer))))))
  (Signals/register "INT"
    (reify Runnable
      (run [_]
        (reset! running? false)))))

(defn- run-event-loop! [^Terminal terminal keymap input-chan running? renderer]
  (let [^Thread input-thread (start-input-thread! terminal keymap input-chan running?)]
    (try
      (loop []
        (when (and @running? (not (:exit? @st/state)))
          (let [[v ch] (a/alts!! [input-chan refresh-chan (a/timeout 12)]
                                 :priority true)]
            (when (= ch refresh-chan)
              (do-render! renderer))
            (when (= ch input-chan)
              (when v
                (case (:type v)
                  :key   (process-key v)
                  :mouse (process-mouse v)
                  nil)
                (do-render! renderer))))
          (recur)))
      @st/state
      (finally
        (.interrupt input-thread)
        (a/close! input-chan)))))

(defn- cleanup-tui! [terminal original-attrs renderer input-chan]
  (try (screener/stop-watcher!) (catch Throwable _))
  (when renderer (render/stop! renderer))
  (when terminal
    (term/set-attributes terminal original-attrs)
    (term/close terminal)))

(defn start!
  ([] (start! {}))
  ([{:keys [ds account-id ibkr-config profile initial-message] :as config}]
   (init-tui-state! config)
   (let [{:keys [^Terminal terminal original-attrs renderer keymap]
          :as _tenv}     (init-terminal)
         running?        (atom true)
         input-chan      (a/chan 64)
         last-size       (atom {:width @state-width :height @state-height})]
     (register-signal-handlers! terminal renderer running? last-size)
     (render/start! renderer)
     (do-render! renderer)
     (try
       (run-event-loop! terminal keymap input-chan running? renderer)
       (finally
         (cleanup-tui! terminal original-attrs renderer input-chan))))))

(defn -main [& _]
  (start! {:initial-message "Welcome to Options Trader. Type /help for commands, /quit to exit."}))