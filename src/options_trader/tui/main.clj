(ns options-trader.tui.main
  "Charm.clj-based terminal UI. Uses Elm architecture with init/update/view."
  (:require [cheshire.core :as json]
            [charm.message :as msg]
            [charm.program :as program]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.data.ibkr :as ibkr]
            [options-trader.portfolio.core :as portfolio]
            [options-trader.tui.conversation :as conv]
            [options-trader.tui.llm :as llm]
            [options-trader.tui.ibkr :as tui-ibkr]
            [options-trader.tui.render :as render]
            [options-trader.tui.state :as st])
  (:import [java.io BufferedReader InputStreamReader PrintWriter]))

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

(defn handle-event [ev sid-atom]
  (let [t (:type ev)
        msg (:message ev)
        content (:content msg)]
    (when-let [sid (:session_id ev)]
      (reset! sid-atom sid))
    (case t
      "assistant"
      (do
        (doseq [th (thinking-blocks content)]
          (st/append-activity! :thinking th))
        (doseq [tu (tool-use-blocks content)]
          (st/append-activity! :tool (tool-summary tu)))
        (doseq [t (text-blocks content)]
          (st/append-message! :assistant t)))
      "result"
      (when (:is_error ev)
        (st/append-activity! :system (str "claude error: " (:error ev "unknown"))))
      nil)))

(defn spawn-claude! [user-msg]
  (let [model    (:model @st/state)
        provider (:provider @st/state :claude)
        scope    (:scope @st/state)
        prior    (conv/current-claude-session scope)
        sid      (atom nil)
        cwd      (System/getProperty "user.dir")]
    (st/set-streaming! true)
    (st/append-message! :user user-msg)
    (a/thread
      (try
        (let [claude-args (cond-> ["--print" "--output-format" "stream-json"
                                   "--verbose" "--dangerously-skip-permissions"]
                            (:claude-session-id prior)
                            (conj "--resume" (:claude-session-id prior)))
              {:keys [cmd env]} (llm/streaming-invocation
                                  {:model model :provider provider :claude-args claude-args})
              pb       (doto (ProcessBuilder. ^java.util.List cmd)
                         (.directory (io/file cwd))
                         (.redirectErrorStream false))
              _        (let [pe (.environment pb)]
                         (doseq [[k v] env] (.put pe k v)))
              proc     (.start pb)]
          (with-open [writer (PrintWriter. (.getOutputStream proc) true)]
            (.println writer user-msg))
          (with-open [reader (BufferedReader. (InputStreamReader. (.getInputStream proc)))]
            (loop []
              (when-let [line (.readLine reader)]
                (when-let [ev (parse-event line)]
                  (handle-event ev sid))
                (recur))))
          (.waitFor proc)
          (when-let [s @sid]
            (conv/record-claude-session! scope s)))
        (catch Throwable t
          (st/append-message! :system (str "claude error: " (.getMessage t))))
        (finally
          (st/set-streaming! false))))))

(defn handle-quit [_args _ds]
  (st/quit!))

(defn handle-refresh [_args ds]
  (try
    (refresh-portfolio! ds)
    (st/append-message! :system "portfolio refreshed from DB")
    (catch Throwable t
      (st/append-message! :system (str "refresh failed: " (.getMessage t))))))

(defn handle-model [args _ds]
  (if-let [m (first args)]
    (let [[provider model] (if (str/includes? m ":")
                             (let [[p mm] (str/split m #":" 2)] [(keyword p) mm])
                             [(:provider @st/state :claude) m])]
      (try
        (llm/set-provider! provider)
        (llm/set-model! model)
        (swap! st/state assoc :model model :provider provider)
        (st/append-message! :system (str "model set to " (name provider) ":" model))
        (catch Exception e
          (st/append-message! :system (str "bad /model: " (.getMessage e))))))
    (st/append-message! :system
      (str "current: " (name (:provider @st/state :claude)) ":" (:model @st/state)))))

(defn handle-connect [_args ds]
  (tui-ibkr/connect-async! {:host "127.0.0.1" :port 7497 :client-id 7 :ds ds}))

(defn handle-disconnect [_args _ds]
  (tui-ibkr/disconnect!))

(defn handle-investigate [args _ds]
  (if-let [sym (some-> (first args) str/upper-case str/trim)]
    (do (swap! st/state assoc :scope (keyword sym))
        (st/append-message! :system (str "scope = " sym)))
    (st/append-message! :system "usage: /investigate <SYMBOL>")))

(defn handle-clear-investigation [_args _ds]
  (swap! st/state assoc :scope :scratch)
  (st/append-message! :system "scope = scratch"))

(defn handle-reset [_args _ds]
  (conv/clear-claude-session! (:scope @st/state))
  (st/append-message! :system "claude session reset for current scope"))

(defn handle-sessions [_args _ds]
  (let [rows (conv/list-claude-sessions)]
    (if (empty? rows)
      (st/append-message! :system "no claude sessions tracked")
      (doseq [{:keys [scope-key tokens-input turn-count]} rows]
        (st/append-message! :system
          (format "  %s  tokens=%d  turns=%d"
                  (name scope-key) (or tokens-input 0) (or turn-count 0)))))))

(defn handle-help [_args _ds]
  (st/append-message! :system
    (str/join "\n"
      ["available commands:"
       "  /quit | /exit | /q              exit the TUI"
       "  /refresh                        re-read positions from DB"
       "  /connect | /disconnect          TWS connection"
       "  /model [id]                     show or switch Claude model"
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
      (st/append-message! :system (str "unknown command: " cmd)))))

(defonce ^:private ds-atom (atom nil))

(defn on-enter [state ds]
  (let [line (str/trim (:input state))]
    (when-not (str/blank? line)
      (st/clear-input!)
      (cond
        (str/starts-with? line "/") (dispatch-slash line ds)
        (:streaming? state)        (st/append-message! :system "still streaming")
        :else                       (spawn-claude! line)))))

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

(def state-width (atom 80))
(def state-height (atom 24))

(defn update-fn [state message]
  (cond
    (msg/quit? message)
    (do (try (tui-ibkr/disconnect!) (catch Throwable _))
        [state program/quit-cmd])

    (msg/window-size? message)
    (do (reset! state-width (:width message))
        (reset! state-height (:height message))
        [state nil])

    (and (msg/key-press? message) (msg/ctrl? message) (msg/key-match? message "c"))
    (do (try (tui-ibkr/disconnect!) (catch Throwable _))
        [state program/quit-cmd])

    (msg/key-match? message :enter)
    (do (on-enter @st/state @ds-atom)
        [state nil])

    (msg/key-match? message :backspace)
    (do (on-backspace @st/state)
        [state nil])

    (msg/key-match? message :left)
    (do (on-left @st/state)
        [state nil])

    (msg/key-match? message :right)
    (do (on-right @st/state)
        [state nil])

    (msg/key-match? message :home)
    (do (on-home @st/state)
        [state nil])

    (msg/key-match? message :end)
    (do (on-end @st/state)
        [state nil])

    (msg/key-press? message)
    (if-let [ch (:key message)]
      (do (on-char @st/state ch)
          [state nil])
      [state nil])

    :else [state nil]))

(defn view [_state]
  (render/render @st/state @state-width @state-height))

(defn init []
  (let [s @st/state]
    [s nil]))

(defn start!
  ([] (start! {}))
  ([{:keys [ds account-id ibkr-config initial-message]}]
   (st/reset-state!)
   (reset! ds-atom ds)
   (when account-id (swap! st/state assoc :account-id account-id))
   (when initial-message (st/append-message! :system initial-message))
   (when ds
     (try (refresh-portfolio! ds)
          (catch Throwable t
            (st/append-message! :system (str "portfolio load skipped: " (.getMessage t))))))
   (when (and ds ibkr-config)
     (tui-ibkr/connect-async! (assoc ibkr-config :ds ds)))
   (program/run {:init init
                 :update update-fn
                 :view view
                 :alt-screen true
                 :hide-cursor false})))

(defn -main [& _]
  (start! {:initial-message "Welcome to Options Trader. Type /help for commands, /quit to exit."}))