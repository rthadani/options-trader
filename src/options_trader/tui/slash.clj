(ns options-trader.tui.slash
  "Local slash-command dispatch for the TUI.

   Most commands here are still stubs that return :ok — they're recognised by
   the dispatch table so the TUI doesn't reject them, and the concrete
   behaviour is wired in the namespace that owns the side effect (screener,
   actions, etc.). The scope / claude-session commands are real here because
   their state lives in tui.conversation and the slash layer is the natural
   trigger point."
  (:require [clojure.string :as str]
            [options-trader.tui.conversation :as conv]
            [options-trader.tui.llm          :as llm]))

(defn- stub-handler [cmd]
  (fn [_ctx args]
    {:cmd cmd :args args :status :ok}))

(defn- normalise-symbol
  "Tickers are upper-case in the rest of the system; normalise here so
   /investigate aapl and /investigate AAPL hit the same scope."
  [s]
  (when s (str/upper-case (str/trim s))))

;;; ── Scope / claude-session handlers (real) ──────────────────────────────────

(defn- handle-investigate
  "/investigate <SYMBOL> — set active scope to SYMBOL. If a prior claude
   session exists for that symbol, the next message resumes it; otherwise
   a fresh claude session starts and gets stored under that symbol."
  [_ctx args]
  (if-let [sym (normalise-symbol (first args))]
    (let [prior (conv/current-claude-session sym)]
      {:cmd       "/investigate"
       :args      args
       :status    :ok
       :command   :set-scope
       :scope-key sym
       :resumed?  (boolean (:claude-session-id prior))})
    {:cmd     "/investigate"
     :args    args
     :status  :ok
     :command :set-scope-error
     :error   "missing symbol — usage: /investigate <TICKER>"}))

(defn- handle-clear-investigation
  "/clear-investigation — switch active scope to :scratch (the unscoped
   research session). Existing per-symbol bindings are untouched and can be
   returned to via /investigate."
  [_ctx _args]
  {:cmd       "/clear-investigation"
   :status    :ok
   :command   :set-scope
   :scope-key :scratch
   :resumed?  (boolean (:claude-session-id (conv/current-claude-session :scratch)))})

(defn- handle-reset
  "/reset — drop the claude session-id binding for the current scope. Next
   message in this scope starts a fresh claude session. The underlying
   JSONL file on disk is left in place for manual recovery."
  [{:keys [scope-key persist-path] :or {scope-key :scratch}} _args]
  (let [prior (conv/current-claude-session scope-key)]
    (conv/clear-claude-session! scope-key)
    (conv/persist-claude-sessions! (or persist-path conv/default-persist-path))
    {:cmd              "/reset"
     :status           :ok
     :command          :reset-scope
     :scope-key        scope-key
     :prior-session-id (:claude-session-id prior)}))

(defn- handle-sessions
  "/sessions — list all known scope → claude-session bindings with usage stats.
   Each row carries :threshold-state so the UI can colour entries that are
   near their compaction threshold."
  [_ctx _args]
  {:cmd     "/sessions"
   :status  :ok
   :command :list-sessions
   :rows    (mapv (fn [row]
                    (assoc row :threshold-state
                           (conv/threshold-state (:scope-key row))))
                  (conv/list-claude-sessions))})

(defn- handle-compact
  "/compact — request compaction for the current scope. The handler itself
   is a no-op on conversation history; it returns an intent the TUI driver
   acts on by sending '/compact' to claude (option 1) and, if that doesn't
   condense within a heuristic, falling back to summarise-and-restart
   (option 2)."
  [{:keys [scope-key] :or {scope-key :scratch}} _args]
  (let [prior (conv/current-claude-session scope-key)]
    {:cmd          "/compact"
     :status       :ok
     :command      :compact-scope
     :scope-key    scope-key
     :prior-tokens (:tokens-input prior 0)
     :threshold    (conv/threshold-state scope-key)
     :has-session? (boolean (:claude-session-id prior))}))

(defn- handle-model
  "/model                    — show the active provider + model
   /model <id>               — switch model on the current provider
                               (e.g. claude-opus-4-5, claude-haiku-4-5-20251001)
   /model <provider>:<id>    — switch provider + model
                               (e.g. kimi:kimi-k2.5, ollama:qwen2.5-coder,
                               minimax:MiniMax-M2.7, claude:claude-opus-4-5)"
  [_ctx args]
  (if-let [m (first args)]
    (if (str/includes? m ":")
      (let [[prov mod] (str/split m #":" 2)
            p     (llm/set-provider! prov)
            new-m (llm/set-model! mod)]
        {:cmd "/model" :status :ok :command :set-model :provider p :model new-m})
      (let [new-m (llm/set-model! m)]
        {:cmd "/model" :status :ok :command :set-model
         :provider (llm/current-provider) :model new-m}))
    {:cmd "/model" :status :ok :command :show-model
     :provider (llm/current-provider) :model (llm/current-model)}))

;;; ── Dispatch table ──────────────────────────────────────────────────────────

(def dispatch-table
  {"/help"                (stub-handler "/help")
   "/clear"               (stub-handler "/clear")
   "/history"             (stub-handler "/history")
   "/resume"              (stub-handler "/resume")
   "/run"                 (stub-handler "/run")
   "/screens"             (stub-handler "/screens")
   "/universes"           (stub-handler "/universes")
   "/investigate"         handle-investigate
   "/clear-investigation" handle-clear-investigation
   "/note"                (stub-handler "/note")
   "/findings"            (stub-handler "/findings")
   "/refresh"             (stub-handler "/refresh")
   "/portfolio"           (stub-handler "/portfolio")
   "/watchlist"           (stub-handler "/watchlist")
   "/promote"             (stub-handler "/promote")
   "/demote"              (stub-handler "/demote")
   "/model"               handle-model
   "/quit"                (stub-handler "/quit")
   "/reset"               handle-reset
   "/sessions"            handle-sessions
   "/compact"             handle-compact})

(def required-commands
  "Canonical set of slash commands the TUI must recognise."
  #{"/help" "/clear" "/history" "/resume" "/run"
    "/screens" "/universes" "/investigate" "/clear-investigation"
    "/note" "/findings" "/refresh" "/portfolio" "/watchlist"
    "/promote" "/demote" "/model" "/quit"
    "/reset" "/sessions" "/compact"})

(defn dispatch
  "Parse input as a slash command and dispatch to the registered handler.
   ctx is the TUI's current state map; handlers may read :scope-key,
   :persist-path, etc. Returns {:cmd :args :status :ok ...} on success or
   {:cmd :status :unknown-command} otherwise."
  [ctx input]
  (let [trimmed (str/trim input)
        parts   (str/split trimmed #"\s+" 2)
        cmd     (first parts)
        args    (vec (when-let [rest-str (second parts)]
                       (str/split rest-str #"\s+")))]
    (if-let [handler (get dispatch-table cmd)]
      (handler ctx args)
      {:cmd cmd :status :unknown-command})))

(defn known-command?
  "Return true if cmd string is in the dispatch table."
  [cmd]
  (contains? dispatch-table cmd))
