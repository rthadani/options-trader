(ns options-trader.tui.main
  "Lanterna-based terminal UI. Owns the screen lifecycle, the keystroke loop,
   the redraw pipeline, and the Claude subprocess driver."
  (:require [cheshire.core :as json]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.portfolio.core :as portfolio]
            [options-trader.tui.conversation :as conv]
            [options-trader.tui.ibkr :as tui-ibkr]
            [options-trader.tui.render :as render]
            [options-trader.tui.state  :as st])
  (:import [com.googlecode.lanterna TerminalPosition]
           [com.googlecode.lanterna.input KeyStroke KeyType]
           [com.googlecode.lanterna.screen Screen TerminalScreen]
           [com.googlecode.lanterna.terminal DefaultTerminalFactory Terminal]
           [java.io BufferedReader InputStreamReader PrintWriter]))

;;; ── Screen lifecycle ────────────────────────────────────────────────────────

(defn- open-screen ^Screen []
  (let [factory (DefaultTerminalFactory.)
        terminal ^Terminal (.createTerminal factory)
        screen   (TerminalScreen. terminal)]
    (.startScreen screen)
    screen))

(defn- close-screen [^Screen screen]
  (try (.stopScreen screen) (catch Throwable _))
  (try (.close screen)      (catch Throwable _)))

;;; ── Drawing ────────────────────────────────────────────────────────────────

(defn- draw! [^Screen screen state]
  (let [size (.getTerminalSize screen)
        w    (.getColumns size)
        h    (.getRows size)
        ops  (render/render state w h)
        tg   (.newTextGraphics screen)
        cur  (render/cursor-position state h)]
    (.clear screen)
    (doseq [{:keys [row col text]} ops]
      (when (and (>= row 0) (< row h))
        (.putString tg ^int col ^int row ^String text)))
    (.setCursorPosition screen
      (TerminalPosition. (int (min (dec w) (:col cur)))
                         (int (min (dec h) (:row cur)))))
    (.refresh screen)))

;;; ── Portfolio loading ─────────────────────────────────────────────────────

(defn- default-account-id [ds]
  (or (-> (jdbc/execute! ds
            ["SELECT DISTINCT account FROM positions LIMIT 1"]
            {:builder-fn rs/as-unqualified-lower-maps})
          first :account)
      (-> (jdbc/execute! ds
            ["SELECT account FROM account_summary
              ORDER BY fetched_at DESC LIMIT 1"]
            {:builder-fn rs/as-unqualified-lower-maps})
          first :account)))

(defn refresh-portfolio!
  "Read positions + account_summary from DB into the state atom. No IBKR call."
  [ds]
  (let [account-id (or (:account-id @st/state)
                       (default-account-id ds))
        store      (portfolio/->JdbcStore ds)
        positions  (when account-id (portfolio/read-positions store account-id))
        summary    (when account-id (portfolio/read-account-summary store account-id))]
    (st/set-portfolio! {:positions       positions
                        :account-summary summary
                        :account-id      account-id})))

;;; ── Claude subprocess ─────────────────────────────────────────────────────

(defn- parse-event [line]
  (try (json/parse-string line true) (catch Exception _ nil)))

(defn- text-blocks [content]
  (->> content
       (filter #(= "text" (:type %)))
       (map :text)))

(defn- tool-use-blocks [content]
  (->> content
       (filter #(= "tool_use" (:type %)))))

(defn- handle-event [ev session-atom]
  (let [t (:type ev)
        msg (:message ev)
        content (:content msg)]
    (when-let [sid (:session_id ev)]
      (reset! session-atom sid))
    (case t
      "assistant"
      (do
        (doseq [tu (tool-use-blocks content)]
          (st/append-message! :system (str "[tool: " (:name tu) "]")))
        (doseq [t (text-blocks content)]
          (st/append-message! :assistant t)))
      "result"
      (when (:is_error ev)
        (st/append-message! :system (str "claude error: "
                                         (:error ev "unknown"))))
      ;; ignore user / system / unknown events
      nil)))

(defn- spawn-claude! [user-msg]
  (let [model (:model @st/state)
        scope (:scope @st/state)
        prior (conv/current-claude-session scope)
        sid   (atom nil)
        cwd   (System/getProperty "user.dir")]
    (st/set-streaming! true)
    (st/append-message! :user user-msg)
    (a/thread
      (try
        (let [base-cmd ["claude" "--print"
                        "--output-format" "stream-json"
                        "--verbose"
                        "--model" model
                        "--dangerously-skip-permissions"]
              cmd      (if-let [resume-id (:claude-session-id prior)]
                         (conj base-cmd "--resume" resume-id)
                         base-cmd)
              pb       (doto (ProcessBuilder. ^java.util.List cmd)
                         (.directory (io/file cwd))
                         (.redirectErrorStream false))
              proc     (.start pb)]
          (with-open [writer (PrintWriter. (.getOutputStream proc) true)]
            (.println writer user-msg))
          (with-open [reader (BufferedReader.
                               (InputStreamReader. (.getInputStream proc)))]
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

;;; ── Slash-command dispatch ────────────────────────────────────────────────

(defn- handle-quit [_args _ds]
  (st/quit!))

(defn- handle-refresh [_args ds]
  (try
    (refresh-portfolio! ds)
    (st/append-message! :system "portfolio refreshed from DB")
    (catch Throwable t
      (st/append-message! :system (str "refresh failed: " (.getMessage t))))))

(defn- handle-model [args _ds]
  (if-let [m (first args)]
    (do (swap! st/state assoc :model m)
        (st/append-message! :system (str "model set to " m)))
    (st/append-message! :system (str "current model: " (:model @st/state)))))

(defn- handle-connect [_args ds]
  (tui-ibkr/connect-async!
    {:host "127.0.0.1" :port 7497 :client-id 7 :ds ds}))

(defn- handle-disconnect [_args _ds]
  (tui-ibkr/disconnect!))

(defn- handle-investigate [args _ds]
  (if-let [sym (some-> (first args) str/upper-case str/trim)]
    (do (swap! st/state assoc :scope (keyword sym))
        (st/append-message! :system (str "scope = " sym)))
    (st/append-message! :system "usage: /investigate <SYMBOL>")))

(defn- handle-clear-investigation [_args _ds]
  (swap! st/state assoc :scope :scratch)
  (st/append-message! :system "scope = scratch"))

(defn- handle-reset [_args _ds]
  (conv/clear-claude-session! (:scope @st/state))
  (st/append-message! :system "claude session reset for current scope"))

(defn- handle-sessions [_args _ds]
  (let [rows (conv/list-claude-sessions)]
    (if (empty? rows)
      (st/append-message! :system "no claude sessions tracked")
      (doseq [{:keys [scope-key tokens-input turn-count]} rows]
        (st/append-message! :system
          (format "  %s  tokens=%d  turns=%d"
                  (name scope-key) (or tokens-input 0) (or turn-count 0)))))))

(defn- handle-help [_args _ds]
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

(defn- dispatch-slash [line ds]
  (let [parts (str/split (str/trim line) #"\s+")
        cmd   (first parts)
        args  (rest parts)]
    (if-let [h (get command-table cmd)]
      (h args ds)
      (st/append-message! :system (str "unknown command: " cmd)))))

;;; ── Input handling ────────────────────────────────────────────────────────

(defn- on-enter [state ds]
  (let [line (str/trim (:input state))]
    (when-not (str/blank? line)
      (st/clear-input!)
      (cond
        (str/starts-with? line "/") (dispatch-slash line ds)
        (:streaming? state)         (st/append-message! :system
                                      "still streaming — wait for response")
        :else                       (spawn-claude! line)))))

(defn- on-backspace [state]
  (let [{:keys [input cursor]} state]
    (when (pos? cursor)
      (let [c (int cursor)]
        (swap! st/state assoc
               :input  (str (subs input 0 (dec c)) (subs input c))
               :cursor (dec c))))))

(defn- on-char [state ^Character ch]
  (let [{:keys [input cursor]} state
        c (int cursor)]
    (swap! st/state assoc
           :input  (str (subs input 0 c) ch (subs input c))
           :cursor (inc c))))

(defn- on-left [_state]
  (swap! st/state update :cursor #(max 0 (dec %))))

(defn- on-right [state]
  (let [c (:cursor state) n (count (:input state))]
    (swap! st/state assoc :cursor (min n (inc c)))))

(defn- on-home [_state]
  (swap! st/state assoc :cursor 0))

(defn- on-end [state]
  (swap! st/state assoc :cursor (count (:input state))))

(defn- handle-keystroke [^KeyStroke ks state ds]
  (let [kt (.getKeyType ks)]
    (cond
      (or (= kt KeyType/EOF)
          (and (= kt KeyType/Character)
               (.isCtrlDown ks)
               (some-> (.getCharacter ks) (= \c))))
      (st/quit!)

      (= kt KeyType/Enter)      (on-enter state ds)
      (= kt KeyType/Backspace)  (on-backspace state)
      (= kt KeyType/ArrowLeft)  (on-left state)
      (= kt KeyType/ArrowRight) (on-right state)
      (= kt KeyType/Home)       (on-home state)
      (= kt KeyType/End)        (on-end state)
      (= kt KeyType/Character)
      (when-let [ch (.getCharacter ks)]
        (on-char state ch))
      :else :nop)))

;;; ── Main loop with async input + state-watcher redraws ────────────────────

(defn- input-thread [^Screen screen ks-chan]
  (a/thread
    (try
      (loop []
        (let [ks (.readInput screen)]
          (a/>!! ks-chan ks)
          (when ks (recur))))
      (catch Throwable _))))

(defn- run-loop [^Screen screen ds]
  (let [ks-chan     (a/chan 32)
        redraw-chan (a/chan (a/dropping-buffer 1))
        watch-key   ::redraw]
    (add-watch st/state watch-key
      (fn [_ _ _ _] (a/offer! redraw-chan true)))
    (input-thread screen ks-chan)
    (try
      (draw! screen @st/state)
      (loop []
        (let [[v ch] (a/alts!! [ks-chan redraw-chan])]
          (cond
            (and (= ch ks-chan) v)
            (handle-keystroke v @st/state ds)

            ;; redraw-chan tick: no input, just redraw below
            :else nil))
        (draw! screen @st/state)
        (when-not (:exit? @st/state) (recur)))
      (finally
        (remove-watch st/state watch-key)
        (a/close! ks-chan)
        (a/close! redraw-chan)))))

;;; ── Entry point ──────────────────────────────────────────────────────────

(defn start!
  "Open the terminal, load cached portfolio from ds, run the event loop.
   Returns the final state map.

   opts:
     :ds              datasource for portfolio reads (required for /refresh)
     :account-id      preferred IBKR account id; falls back to DB scan
     :ibkr-config     {:host :port :client-id} — if present, connect on start
     :initial-message system message rendered at start"
  ([] (start! {}))
  ([{:keys [ds account-id ibkr-config initial-message]}]
   (st/reset-state!)
   (when account-id (swap! st/state assoc :account-id account-id))
   (when initial-message (st/append-message! :system initial-message))
   (when ds
     (try (refresh-portfolio! ds)
          (catch Throwable t
            (st/append-message! :system
              (str "portfolio load skipped: " (.getMessage t))))))
   (when (and ds ibkr-config)
     (tui-ibkr/connect-async! (assoc ibkr-config :ds ds)))
   (let [screen (open-screen)]
     (try
       (run-loop screen ds)
       @st/state
       (finally
         (try (tui-ibkr/disconnect!) (catch Throwable _))
         (close-screen screen))))))

(defn -main [& _]
  (start! {:initial-message "Welcome to Options Trader. Type /help for commands, /quit to exit."}))
