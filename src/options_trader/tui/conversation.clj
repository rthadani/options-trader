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
            [clojure.java.io :as io])
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
                  :tokens-input  long
                  :tokens-output long
                  :turn-count    long
                  :updated-at    ms}}

   scope-key is a ticker symbol string or :scratch."
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
   disk is NOT touched — it stays in ~/.claude/projects/ for inspection or
   manual recovery; only the in-process binding is forgotten. Next message in
   this scope will spawn fresh."
  [scope-key]
  (swap! claude-sessions dissoc scope-key)
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
