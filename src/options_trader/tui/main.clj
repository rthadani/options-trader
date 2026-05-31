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
            [options-trader.portfolio.core :as portfolio]
            [options-trader.tui.conversation :as conv]
            [options-trader.tui.llm :as llm]
            [options-trader.tui.pi-proc :as pi-proc]
            [options-trader.tui.runtime-context :as rt-ctx]
            [options-trader.tui.ibkr :as tui-ibkr]
            [options-trader.tui.render :as render-ui]
            [options-trader.tui.state :as st])
  (:import [org.jline.terminal Terminal]
           [org.jline.utils Signals]))

(defonce ^:private refresh-chan (a/chan 64))
(defonce ^:private ds-atom (atom nil))
(def state-width (atom 80))
(def state-height (atom 24))

(defn default-account-id [ds]
  (or (ibkr/default-account)
      (-> (jdbc/execute! ds
            ["SELECT DISTINCT account FROM positions LIMIT 1"]
            {:builder-fn rs/as-unqualified-lower-maps})
          first :account)
      (-> (jdbc/execute! ds
            ["SELECT account FROM account_summary
              ORDER BY fetched_at DESC LIMIT 1"]
            {:builder-fn rs/as-unqualified-lower-maps})
          first :account)))

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
  (try (json/parse-string line true) (catch Exception _ nil)))

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

;;; ── Claude event handling ─────────────────────────────────────────────────

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
        (doseq [th (thinking-blocks content)]
          (st/append-activity! :thinking th)
          (a/>!! refresh-chan :refresh))
        (doseq [tu (tool-use-blocks content)]
          (st/append-activity! :tool (tool-summary tu))
          (a/>!! refresh-chan :refresh))
        (doseq [t (text-blocks content)]
          (st/append-message! :assistant t)
          (a/>!! refresh-chan :refresh)))
      "result"
      (when (:is_error ev)
        (st/append-activity! :system (str "claude error: " (:error ev "unknown")))
        (a/>!! refresh-chan :refresh))
      nil)))

;;; ── Pi event handling ─────────────────────────────────────────────────────

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
    (let [label (if (:start? ev)
                  (str "🔧 " (:name ev) " "
                       (when-let [a (:args ev)]
                         (let [s (pr-str a)]
                           (if (> (count s) 60) (str (subs s 0 60) "…") s))))
                  (str (if (:error? ev) "✗ " "✓ ") (:name ev)))]
      (st/append-activity! :tool label)
      (a/>!! refresh-chan :refresh))

    :usage
    (let [scope (:scope @st/state)
          usage {:input-tokens  (or (:input-tokens ev) 0)
                 :output-tokens (or (:output-tokens ev) 0)}]
      (conv/record-turn-stats! scope usage))

    nil))

;;; ── Agent spawn ───────────────────────────────────────────────────────────

(defn spawn-agent! [user-msg]
  (let [agent         (:agent @st/state :claude)
        model         (:model @st/state)
        provider      (:provider @st/state :claude)
        scope         (:scope @st/state)
        cwd           (System/getProperty "user.dir")
        additional    (:additional-dirs @st/state [])
        system-prompt (rt-ctx/build {})]
    (st/set-streaming! true)
    (st/append-message! :user user-msg)
    (a/>!! refresh-chan :refresh)
    (a/thread
      (try
        (case agent
          :pi
          (let [spec (pi-proc/spawn-pi {:model model :provider provider
                                        :system-prompt system-prompt
                                        :additional-dirs additional
                                        :cwd cwd})
                pb   (doto (ProcessBuilder. ^java.util.List (:cmd spec))
                       (.directory (io/file cwd))
                       (.redirectErrorStream false))
                proc (.start pb)]
            (try
              (with-open [writer (java.io.PrintWriter. (.getOutputStream proc) true)]
                (.println writer user-msg))
              (let [reader (java.io.BufferedReader.
                             (java.io.InputStreamReader. (.getInputStream proc)))]
                (try
                  (loop []
                    (when-let [line (.readLine reader)]
                      (let [ev (pi-proc/parse-event line)]
                        (when ev (handle-pi-event ev))
                        (when-not (= :done (:type ev))
                          (recur)))))
                  (finally
                    (.close reader)
                    (.destroyForcibly proc)
                    (.waitFor proc 3 java.util.concurrent.TimeUnit/SECONDS))))
              (catch Throwable t
                (.destroyForcibly proc)
                (throw t))))

          ;; default :claude
          (let [prior       (conv/current-claude-session scope)
                claude-args (cond-> ["--print" "--output-format" "stream-json"
                                     "--verbose" "--dangerously-skip-permissions"]
                              system-prompt (conj "--system-prompt" system-prompt)
                              true          (into (mapcat #(vector "--add-dir" %) additional))
                              (:claude-session-id prior)
                              (conj "--resume" (:claude-session-id prior)))
                {:keys [cmd env]} (llm/streaming-invocation
                                    {:model model :provider provider :claude-args claude-args})
                pb       (doto (ProcessBuilder. ^java.util.List cmd)
                           (.directory (io/file cwd))
                           (.redirectErrorStream false))
                _        (let [pe (.environment pb)]
                           (doseq [[k v] env] (.put pe k v)))
                proc     (.start pb)
                sid      (atom nil)]
            (with-open [writer (java.io.PrintWriter. (.getOutputStream proc) true)]
              (.println writer user-msg))
            (with-open [reader (java.io.BufferedReader.
                                 (java.io.InputStreamReader. (.getInputStream proc)))]
              (loop []
                (when-let [line (.readLine reader)]
                  (when-let [ev (parse-event line)]
                    (handle-event ev sid))
                  (recur))))
            (.waitFor proc)
            (when-let [s @sid]
              (conv/record-claude-session! scope s))))

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
    (refresh-portfolio! ds)
    (st/append-chat! :system "portfolio refreshed from DB")
    (catch Throwable t
      (st/append-chat! :system (str "refresh failed: " (.getMessage t))))))

(defn handle-model [args _ds]
  (if-let [m (first args)]
    (let [[provider model] (if (str/includes? m ":")
                             (let [[p mm] (str/split m #":" 2)] [(keyword p) mm])
                             [(:provider @st/state :claude) m])]
      (try
        (llm/set-provider! provider)
        (llm/set-model! model)
        (swap! st/state assoc :model model :provider provider)
        (st/append-chat! :system (str "model set to " (name provider) ":" model))
        (catch Exception e
          (st/append-chat! :system (str "bad /model: " (.getMessage e))))))
    (st/append-chat! :system
      (str "current: " (name (:provider @st/state :claude)) ":" (:model @st/state)
           "  (agent: " (name (:agent @st/state :claude)) ")"))))

(defn handle-agent [args _ds]
  (if-let [a (first args)]
    (try
      (let [new-a (llm/set-agent! a)]
        (swap! st/state assoc :agent new-a)
        (st/append-chat! :system (str "agent set to " (name new-a))))
      (catch Exception e
        (st/append-chat! :system (str "bad /agent: " (.getMessage e)))))
    (st/append-chat! :system
      (str "current agent: " (name (:agent @st/state :claude))))))

(defn handle-connect [_args ds]
  (tui-ibkr/connect-async! {:host "127.0.0.1" :port 7497 :client-id 7 :ds ds}))

(defn handle-disconnect [_args _ds]
  (tui-ibkr/disconnect!))

(defn handle-investigate [args _ds]
  (if-let [sym (some-> (first args) str/upper-case str/trim)]
    (do (swap! st/state assoc :scope (keyword sym))
        (st/append-chat! :system (str "scope = " sym)))
    (st/append-chat! :system "usage: /investigate <SYMBOL>")))

(defn handle-clear-investigation [_args _ds]
  (swap! st/state assoc :scope :scratch)
  (st/append-chat! :system "scope = scratch"))

(defn handle-reset [_args _ds]
  (conv/clear-claude-session! (:scope @st/state))
  (st/append-chat! :system "claude session reset for current scope"))

(defn handle-sessions [_args _ds]
  (let [rows (conv/list-claude-sessions)]
    (if (empty? rows)
      (st/append-chat! :system "no claude sessions tracked")
      (doseq [{:keys [scope-key tokens-input turn-count]} rows]
        (st/append-chat! :system
          (format "  %s  tokens=%d  turns=%d"
                  (name scope-key) (or tokens-input 0) (or turn-count 0)))))))

(defn handle-help [_args _ds]
  (st/append-chat! :system
    (str/join "\n"
      ["available commands:"
       "  /quit | /exit | /q              exit the TUI"
       "  /refresh                        re-read positions from DB"
       "  /connect | /disconnect          TWS connection"
       "  /model [id]                     show or switch model"
       "  /agent [pi|claude]              show or switch agent"
       "  /investigate <SYM>              set conversation scope to SYM"
       "  /clear-investigation            return to :scratch scope"
       "  /reset                          drop claude session for current scope"
       "  /sessions                       list tracked claude sessions"
       "  /help                           this message"])))

(def ^:private command-table
  {"/quit"                handle-quit
   "/exit"                handle-quit
   "/q"                   handle-quit
   "/refresh"             handle-refresh
   "/connect"             handle-connect
   "/disconnect"          handle-disconnect
   "/model"               handle-model
   "/agent"               handle-agent
   "/investigate"         handle-investigate
   "/clear-investigation" handle-clear-investigation
   "/reset"               handle-reset
   "/sessions"            handle-sessions
   "/help"                handle-help})

(defn dispatch-slash [line ds]
  (let [parts (str/split (str/trim line) #"\s+")
        cmd   (first parts)
        args  (rest parts)]
    (if-let [h (get command-table cmd)]
      (h args ds)
      (st/append-chat! :system (str "unknown command: " cmd)))))

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

(defn process-key [event]
  (let [state @st/state
        focus (:focus state)
        k     (:key event)
        ctrl  (:ctrl event)]
    (cond
      (and ctrl (= "c" k))
      (do (try (tui-ibkr/disconnect!) (catch Throwable _))
          (st/quit!))

      ;; Tab toggles focus
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

(defn start!
  ([] (start! {}))
  ([{:keys [ds account-id ibkr-config initial-message]}]
   (st/reset-state!)
   (reset! ds-atom ds)
   (when account-id (swap! st/state assoc :account-id account-id))
   (when initial-message (st/append-chat! :system initial-message))
   (when ds
     (try (refresh-portfolio! ds)
          (catch Throwable t
            (st/append-chat! :system (str "portfolio load skipped: " (.getMessage t))))))
   (when (and ds ibkr-config)
     (tui-ibkr/connect-async! (assoc ibkr-config :ds ds)))

   (let [^Terminal terminal     (term/create-terminal)
         original-attrs         (term/enter-raw-mode terminal)
         {:keys [width height]} (term/get-size terminal)
         renderer               (render/create-renderer terminal
                                                       :fps 60
                                                       :alt-screen true
                                                       :hide-cursor false)
         keymap                 (km/create-keymap terminal)
         running?               (atom true)
         input-chan             (a/chan 64)
         last-size              (atom {:width width :height height})]

     (reset! state-width width)
     (reset! state-height height)

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
                           (reset! running? false))))

     (render/start! renderer)
     (do-render! renderer)

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
                     :key    (process-key v)
                     :mouse  nil
                     :focus  nil
                     :blur   nil
                     nil)
                   (do-render! renderer))))
             (recur)))

         ;; Return final state
         @st/state

         (finally
           (.interrupt input-thread)
           (reset! running? false)
           (a/close! input-chan)
           (render/stop! renderer)
           (term/set-attributes terminal original-attrs)
           (term/close terminal)))))))

(defn -main [& _]
  (start! {:initial-message "Welcome to Options Trader. Type /help for commands, /quit to exit."}))