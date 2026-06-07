(ns options-trader.tui.conversation
  "Two stores live here:

   1. Per-conversation **session** state (existing): UUID-keyed turn log used
      by the TUI for JSONL replay and history navigation.

   2. Per-scope **claude session** state (new): maps a scope-key (ticker symbol
      or :scratch) to the claude CLI session-id used for --resume, plus
      accumulated token usage. This is what makes per-instrument deep-research
      threads resumable across TUI restarts.

   The two stores are independent — one tracks the TUI's own conversation
   history, the other tracks claude's internal session for resumption."
  (:require [cheshire.core   :as json]
            [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [clojure.string  :as str])
  (:import [java.util UUID]))

;;; ── Existing session API (UUID + turns) ─────────────────────────────────────

(def ^:private sessions (atom {}))

(defn new-session!
  "Create and register a new conversation session. Returns the session-id string.
   Opts:
     :model        — model identifier string
     :persist-path — path for JSONL turn persistence (optional)"
  [{:keys [model persist-path] :as _opts}]
  (let [session-id (str (UUID/randomUUID))]
    (swap! sessions assoc session-id
           {:id           session-id
            :turns        []
            :model        model
            :persist-path persist-path
            :created-at   (System/currentTimeMillis)})
    session-id))

(defn append-turn!
  "Append a turn map to the session. Persists to JSONL if :persist-path is set.
   Returns the turn map (with :ts added)."
  [session-id turn]
  (let [turn' (assoc turn :ts (System/currentTimeMillis))]
    (swap! sessions update-in [session-id :turns] conj turn')
    (when-let [path (get-in @sessions [session-id :persist-path])]
      (io/make-parents path)
      (spit path (str (json/generate-string turn') "\n") :append true))
    turn'))

(defn get-session
  "Return the full session map for session-id, or nil if not found."
  [session-id]
  (get @sessions session-id))

(defn session-turns
  "Return the ordered turn vector for session-id."
  [session-id]
  (get-in @sessions [session-id :turns] []))

(defn clear-session!
  "Remove session from the registry. Does not delete the JSONL file."
  [session-id]
  (swap! sessions dissoc session-id))

(defn reset-all!
  "Remove all sessions from the in-memory registry (test helper)."
  []
  (reset! sessions {}))

;;; ── Scope → claude-session store (new) ──────────────────────────────────────

(def ^:private claude-sessions
  "{scope-key → {:claude-session-id str
                  :pi-session-id     str
                  :tokens-input      long
                  :tokens-output     long
                  :turn-count        long
                  :updated-at        ms}}

   scope-key is a ticker symbol string or :scratch. The atom is named for
   historical reasons; it tracks BOTH agents' session ids per scope — only
   the id field differs. Token counts and turn-count are agent-agnostic."
  (atom {}))

(def default-persist-path
  "~/.options-trader/scope-sessions.edn — survives TUI restarts so per-symbol
   deep-research threads can pick up where they left off."
  (str (System/getProperty "user.home") "/.options-trader/scope-sessions.edn"))

(defn current-claude-session
  "Return the {:claude-session-id ... :tokens-input ... :tokens-output ...
   :turn-count ... :updated-at ms} map for scope-key, or nil."
  [scope-key]
  (get @claude-sessions scope-key))

(defn record-claude-session!
  "Persist a claude session-id for scope-key. Idempotent — subsequent turns
   in the same session record the same id; first call seeds the entry."
  [scope-key session-id]
  (swap! claude-sessions update scope-key
         (fn [prev]
           (-> (or prev {:tokens-input 0 :tokens-output 0 :turn-count 0})
               (assoc :claude-session-id session-id
                      :updated-at        (System/currentTimeMillis)))))
  session-id)

(defn record-turn-stats!
  "Add this turn's token usage to scope-key's accumulators. usage is a map
   with :input-tokens and :output-tokens keys (as parsed from claude's
   stream-json result event)."
  [scope-key {:keys [input-tokens output-tokens]
              :or   {input-tokens 0 output-tokens 0}}]
  (swap! claude-sessions update scope-key
         (fn [prev]
           (-> (or prev {:tokens-input 0 :tokens-output 0 :turn-count 0})
               (update :tokens-input  + input-tokens)
               (update :tokens-output + output-tokens)
               (update :turn-count    inc)
               (assoc  :updated-at    (System/currentTimeMillis))))))

(defn clear-claude-session!
  "Drop the claude session-id binding for scope-key. The claude JSONL file on
   disk is NOT touched — it stays in <runtime-claude>/projects/ for inspection
   or manual recovery; only the in-process binding is forgotten. Next claude
   message in this scope will spawn a fresh session. Does NOT touch the
   per-scope pi-session-id."
  [scope-key]
  (swap! claude-sessions update scope-key dissoc :claude-session-id)
  nil)

;;; ── Pi session API (mirrors the claude one) ────────────────────────────────

(defn current-pi-session
  "Return the per-scope session map (same shape as current-claude-session, but
   the pi-side id is under :pi-session-id). Returns nil if the scope is unseen."
  [scope-key]
  (get @claude-sessions scope-key))

(defn record-pi-session!
  "Persist a pi session-id for scope-key. Idempotent."
  [scope-key session-id]
  (swap! claude-sessions update scope-key
         (fn [prev]
           (-> (or prev {:tokens-input 0 :tokens-output 0 :turn-count 0})
               (assoc :pi-session-id session-id
                      :updated-at    (System/currentTimeMillis)))))
  session-id)

(defn ensure-pi-session-id!
  "Return the pi session-id bound to scope-key, generating + recording a new
   UUID if none exists. Pi's `--session-id <id>` creates the session on disk
   when first used, so the TUI can just hand it a deterministic id per scope."
  [scope-key]
  (or (:pi-session-id (current-pi-session scope-key))
      (let [id (str (UUID/randomUUID))]
        (record-pi-session! scope-key id)
        id)))

(defn clear-pi-session!
  "Drop the pi session-id binding for scope-key. The pi session file under
   <pi-session-dir>/ is NOT touched. Does NOT touch claude-session-id."
  [scope-key]
  (swap! claude-sessions update scope-key dissoc :pi-session-id)
  nil)

(defn list-claude-sessions
  "Return a vector of {:scope-key ... :claude-session-id ... :tokens-input ...
   :tokens-output ... :turn-count ... :updated-at ...} maps sorted by recency."
  []
  (->> @claude-sessions
       (map (fn [[k v]] (assoc v :scope-key k)))
       (sort-by :updated-at >)
       vec))

;;; ── Token thresholds ────────────────────────────────────────────────────────

(def default-thresholds
  "Cumulative input-token thresholds that drive compact-suggestion UX.
   Tuned to leave headroom before claude's per-call context limit and before
   pricing gets noticeable."
  {:soft 80000
   :hard 150000})

(defn threshold-state
  "Return :ok | :soft | :hard based on cumulative input tokens for scope-key.
   :hard means a compact is strongly recommended; :soft means it's worth
   surfacing a hint; :ok means business as usual. Accepts custom thresholds."
  ([scope-key] (threshold-state scope-key default-thresholds))
  ([scope-key {:keys [soft hard] :or {soft 80000 hard 150000}}]
   (let [t (:tokens-input (current-claude-session scope-key) 0)]
     (cond
       (>= t hard) :hard
       (>= t soft) :soft
       :else       :ok))))

;;; ── Persistence ─────────────────────────────────────────────────────────────

(defn persist-claude-sessions!
  "Write the scope-session store to disk. Call after every mutation so a
   crash doesn't lose the {scope → session-id} bindings."
  ([] (persist-claude-sessions! default-persist-path))
  ([path]
   (try
     (io/make-parents path)
     (spit path (pr-str @claude-sessions))
     (catch Exception _ nil))))

(defn load-claude-sessions!
  "Restore the scope-session store from disk. Call once at TUI startup.
   Missing or malformed files are ignored — the TUI starts with an empty
   store, not a crash."
  ([] (load-claude-sessions! default-persist-path))
  ([path]
   (try
     (when (.exists (io/file path))
       (reset! claude-sessions (edn/read-string (slurp path))))
     (catch Exception _ nil))))

(defn reset-claude-sessions!
  "Test helper — clear the in-memory scope store. Does NOT touch the persist file."
  []
  (reset! claude-sessions {}))

;;; ── On-disk transcript scanner ──────────────────────────────────────────────

(defn- jsonl-first-user-text
  "Read jsonl until the first user-role message with text content; return a
   single-line preview trimmed to ≤80 chars, or nil. Skips tool-result wrappers
   and synthetic system-reminder lines that don't represent real user input."
  [^java.io.File f]
  (try
    (with-open [rdr (io/reader f)]
      (loop [ls (line-seq rdr)]
        (when-let [line (first ls)]
          (let [obj  (try (json/parse-string line true) (catch Throwable _ nil))
                kind (or (:type obj) (some-> obj :role))
                msg  (:message obj)
                role (or (:role msg) (:role obj))
                content (or (:content msg) (:content obj))
                text (cond
                       (string? content) content
                       (sequential? content)
                       (some #(when (string? (:text %)) (:text %)) content)
                       :else nil)]
            (if (and (or (= "user" kind) (= "user" role))
                     (string? text)
                     (not (str/blank? text))
                     (not (str/starts-with? text "<")))
              (let [t (-> text (str/replace #"\s+" " ") str/trim)]
                (subs t 0 (min 80 (count t))))
              (recur (rest ls)))))))
    (catch Throwable _ nil)))

(defn- jsonl-files [^java.io.File root]
  (when (and root (.isDirectory root))
    (->> (file-seq root)
         (filter (fn [^java.io.File f]
                   (and (.isFile f)
                        (str/ends-with? (.getName f) ".jsonl")
                        (not (str/includes? (.getAbsolutePath f) "/subagents/"))))))))

(defn- claude-id-from-name [^String n]
  (str/replace n #"\.jsonl$" ""))

(defn- pi-id-from-name [^String n]
  (-> n (str/replace #"\.jsonl$" "") (str/split #"_") second))

(defn- text-from-content
  "Coerce a message :content value (string, or array of content blocks) into a
   single text string. Returns nil if nothing text-like is in there — strips
   tool_use / tool_result / thinking / toolCall / toolResult blocks."
  [content]
  (cond
    (string? content)
    (when-not (str/blank? content) content)

    (sequential? content)
    (let [texts (keep #(when (= "text" (:type %)) (:text %)) content)]
      (when (seq texts) (str/join "\n" texts)))

    :else nil))

(defn- claude-event->turn
  "Extract a {:role :text} turn from a claude jsonl event, or nil to skip."
  [{:keys [type message]}]
  (when (and message (#{"user" "assistant"} type))
    (let [role    (:role message)
          content (:content message)
          text    (text-from-content content)]
      (when (and text (#{"user" "assistant"} role))
        {:role (keyword role) :text text}))))

(defn- pi-event->turn
  "Extract a {:role :text} turn from a pi jsonl event, or nil to skip.
   Pi wraps every conversational item under :type \"message\" with :message
   holding {:role :content}. toolResult / toolCall blocks are dropped."
  [{:keys [type message]}]
  (when (and message (= "message" type))
    (let [role    (:role message)
          content (:content message)
          text    (text-from-content content)]
      (when (and text (#{"user" "assistant"} role))
        {:role (keyword role) :text text}))))

(defn replay-turns
  "Read a session jsonl and return an ordered vec of {:role :user|:assistant
   :text str} turns, skipping non-conversational events (tool calls, thinking
   blocks, system inits, queue ops, etc).

   `agent` is :claude or :pi — the on-disk schemas differ."
  [agent ^String path]
  (let [parse (case agent :claude claude-event->turn :pi pi-event->turn)]
    (try
      (with-open [rdr (io/reader path)]
        (into []
              (keep (fn [line]
                      (when-let [obj (try (json/parse-string line true)
                                          (catch Throwable _ nil))]
                        (parse obj))))
              (line-seq rdr)))
      (catch Throwable _ []))))

(defn list-all-transcripts
  "Return a vector of transcript rows for every on-disk jsonl under
   `runtime-claude-dir/projects/**` and `pi-session-dir/`, sorted by mtime desc.

   Each row:
     {:agent       :claude | :pi
      :session-id  string  (derived from filename)
      :file        absolute-path string
      :scope-key   the scope-key currently bound to this session-id, or nil
      :preview     ≤80 chars of the first user message, or nil
      :mtime       file modification time (epoch ms)}"
  [{:keys [runtime-claude-dir pi-session-dir]}]
  (let [sessions  @claude-sessions
        scope-of-claude (into {} (keep (fn [[k v]]
                                         (when-let [id (:claude-session-id v)] [id k]))
                                       sessions))
        scope-of-pi     (into {} (keep (fn [[k v]]
                                         (when-let [id (:pi-session-id v)] [id k]))
                                       sessions))
        claude-root (when runtime-claude-dir (io/file runtime-claude-dir "projects"))
        pi-root     (when pi-session-dir     (io/file pi-session-dir))
        rows (concat
               (for [^java.io.File f (jsonl-files claude-root)
                     :let [sid (claude-id-from-name (.getName f))]]
                 {:agent      :claude
                  :session-id sid
                  :file       (.getAbsolutePath f)
                  :scope-key  (get scope-of-claude sid)
                  :preview    (jsonl-first-user-text f)
                  :mtime      (.lastModified f)})
               (for [^java.io.File f (jsonl-files pi-root)
                     :let [sid (pi-id-from-name (.getName f))]
                     :when sid]
                 {:agent      :pi
                  :session-id sid
                  :file       (.getAbsolutePath f)
                  :scope-key  (get scope-of-pi sid)
                  :preview    (jsonl-first-user-text f)
                  :mtime      (.lastModified f)}))]
    (vec (sort-by :mtime > rows))))
