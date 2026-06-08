(ns options-trader.tui.conversation
  "Per-scope claude+pi session-id store, plus a scanner and replay helper
   for the on-disk jsonl transcripts the two CLIs leave behind. Used by
   /sessions and by the agent-spawn paths in tui.main and tui.llm.

   A scope-key is either a ticker keyword (`:AAPL`) or `:scratch` for the
   unscoped thread. Each scope holds at most one claude session-id and one
   pi session-id, plus running token + turn counters. The store is
   persisted as EDN so a TUI restart picks up where the last run left off."
  (:require [cheshire.core   :as json]
            [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [clojure.string  :as str]
            [options-trader.util :as util])
  (:import [java.util UUID]))

(def ^:private scope-sessions (atom {}))

(def default-persist-path
  (str (System/getProperty "user.home") "/.options-trader/scope-sessions.edn"))

(defn- fresh-record []
  {:tokens-input 0 :tokens-output 0 :turn-count 0})

(defn- set-id! [scope-key id-key id]
  (swap! scope-sessions update scope-key
         (fn [prev]
           (-> (or prev (fresh-record))
               (assoc id-key id :updated-at (System/currentTimeMillis))))))

(defn- clear-id! [scope-key id-key]
  (swap! scope-sessions update scope-key dissoc id-key)
  nil)

(defn current-claude-session
  "Full session record for scope-key, or nil. Both agents' ids live here:
   {:claude-session-id :pi-session-id :tokens-input :tokens-output
    :turn-count :updated-at}."
  [scope-key]
  (get @scope-sessions scope-key))

(defn record-claude-session!
  "Bind a claude session-id to scope-key. Idempotent — re-recording the
   same id is a no-op apart from refreshing :updated-at."
  [scope-key id]
  (set-id! scope-key :claude-session-id id)
  id)

(defn record-pi-session!
  "Bind a pi session-id to scope-key. Idempotent."
  [scope-key id]
  (set-id! scope-key :pi-session-id id)
  id)

(defn clear-claude-session!
  "Forget the claude binding for scope-key. The jsonl on disk is untouched —
   it's still resumable via /sessions if you need to recover it."
  [scope-key]
  (clear-id! scope-key :claude-session-id))

(defn clear-pi-session!
  "Forget the pi binding for scope-key. The jsonl on disk is untouched."
  [scope-key]
  (clear-id! scope-key :pi-session-id))

(defn ensure-pi-session-id!
  "Return the pi session-id for scope-key, generating and recording a fresh
   UUID if none exists. Pi creates the on-disk session lazily on first use
   of `--session-id <id>`, so handing it a deterministic per-scope id is
   enough — no need to consult pi first."
  [scope-key]
  (or (:pi-session-id (current-claude-session scope-key))
      (let [id (str (UUID/randomUUID))]
        (record-pi-session! scope-key id)
        id)))

(defn record-turn-stats!
  "Add this turn's token usage to scope-key's running counters."
  [scope-key {:keys [input-tokens output-tokens]
              :or   {input-tokens 0 output-tokens 0}}]
  (swap! scope-sessions update scope-key
         (fn [prev]
           (-> (or prev (fresh-record))
               (update :tokens-input  + input-tokens)
               (update :tokens-output + output-tokens)
               (update :turn-count    inc)
               (assoc  :updated-at    (System/currentTimeMillis))))))

(defn persist-claude-sessions!
  "Snapshot the store to disk so a restart can re-bind every scope's
   session ids. Failures are swallowed; losing the index is annoying but
   not fatal — the worst case is a fresh session next prompt."
  ([] (persist-claude-sessions! default-persist-path))
  ([path]
   (util/safe-spit path (pr-str @scope-sessions))))

(defn load-claude-sessions!
  "Hydrate the store from disk. Missing or malformed files leave the store
   empty rather than crash the TUI on startup."
  ([] (load-claude-sessions! default-persist-path))
  ([path]
   (when-let [data (and (.exists (io/file path))
                        (util/safe-edn-read (slurp path)))]
     (reset! scope-sessions data))))

(defn reset-claude-sessions!
  "Test helper. Clears the in-memory store; the persist file is untouched."
  []
  (reset! scope-sessions {}))


;; Per-agent jsonl quirks. Claude tags conversational events as
;; :type "user"/"assistant" with the actual message under :message, and
;; names files <session-uuid>.jsonl. Pi tags everything as :type "message"
;; with role inside :message, and names files <timestamp>_<session-uuid>.jsonl.
(def ^:private agent-spec
  {:claude {:id-from-name #(str/replace % #"\.jsonl$" "")
            :conv-types   #{"user" "assistant"}
            :id-key       :claude-session-id}
   :pi     {:id-from-name #(some-> % (str/replace #"\.jsonl$" "")
                                     (str/split #"_") second)
            :conv-types   #{"message"}
            :id-key       :pi-session-id}})

(defn- text-from-content
  "Reduce a message :content (string or array of content blocks) to a single
   text string. Only :type \"text\" blocks survive — tool_use/tool_result/
   thinking/toolCall/toolResult blocks are dropped."
  [content]
  (cond
    (string? content)
    (when-not (str/blank? content) content)

    (sequential? content)
    (let [texts (keep #(when (= "text" (:type %)) (:text %)) content)]
      (when (seq texts) (str/join "\n" texts)))

    :else nil))

(defn- event->turn
  "Project one jsonl event to a {:role :text} turn, or nil to skip non-
   conversational noise (system inits, tool plumbing, model-change events)."
  [agent {:keys [type message]}]
  (let [{:keys [conv-types]} (agent-spec agent)]
    (when (and message (conv-types type))
      (let [role (:role message)
            text (text-from-content (:content message))]
        (when (and text (#{"user" "assistant"} role))
          {:role (keyword role) :text text})))))

(defn replay-turns
  "Read the jsonl at `path` and return an ordered vector of conversational
   turns. agent is :claude or :pi. Corrupt or missing files yield an empty
   vector — the caller can show \"0 turns\" instead of crashing."
  [agent ^String path]
  (try
    (with-open [rdr (io/reader path)]
      (into []
            (keep (fn [line]
                    (when-let [obj (util/safe-json-parse line)]
                      (event->turn agent obj))))
            (line-seq rdr)))
    (catch Throwable _ [])))

(defn- first-user-preview
  "≤80-char one-line preview of the first real user message in the jsonl, or
   nil. Skips synthesised tool-result wrappers and tag-prefixed reminder
   lines that don't represent the user's actual input."
  [^java.io.File f]
  (try
    (with-open [rdr (io/reader f)]
      (some (fn [line]
              (when-let [obj (util/safe-json-parse line)]
                (let [kind (or (:type obj) (some-> obj :role))
                      msg  (:message obj)
                      role (or (:role msg) (:role obj))
                      text (text-from-content (or (:content msg) (:content obj)))]
                  (when (and text
                             (or (= "user" kind) (= "user" role))
                             (not (str/starts-with? text "<")))
                    (let [t (-> text (str/replace #"\s+" " ") str/trim)]
                      (subs t 0 (min 80 (count t))))))))
            (line-seq rdr)))
    (catch Throwable _ nil)))

(defn- jsonl-files
  "Every .jsonl under root, recursively, except anything inside a subagents/
   subdirectory (those are auxiliary, not user-resumable conversations)."
  [^java.io.File root]
  (when (and root (.isDirectory root))
    (filter (fn [^java.io.File f]
              (and (.isFile f)
                   (str/ends-with? (.getName f) ".jsonl")
                   (not (str/includes? (.getAbsolutePath f) "/subagents/"))))
            (file-seq root))))

(defn- scope-of-by-id
  "Reverse-index the store: {session-id → scope-key} for the given id-key."
  [id-key]
  (into {} (keep (fn [[scope record]]
                   (when-let [id (get record id-key)] [id scope]))
                 @scope-sessions)))

(defn- rows-for
  "Build transcript rows for one agent's transcript directory."
  [agent ^java.io.File root]
  (let [{:keys [id-from-name id-key]} (agent-spec agent)
        scope-of (scope-of-by-id id-key)]
    (for [^java.io.File f (jsonl-files root)
          :let [sid (id-from-name (.getName f))]
          :when sid]
      {:agent      agent
       :session-id sid
       :file       (.getAbsolutePath f)
       :scope-key  (get scope-of sid)
       :preview    (first-user-preview f)
       :mtime      (.lastModified f)})))

(defn list-all-transcripts
  "Transcript rows for every on-disk jsonl, sorted newest first.
   Each row: {:agent :session-id :file :scope-key :preview :mtime}.
   :scope-key is nil for orphans (jsonl on disk with no binding in the store)."
  [{:keys [runtime-claude-dir pi-session-dir]}]
  (let [claude-root (some-> runtime-claude-dir (io/file "projects"))
        pi-root     (some-> pi-session-dir     io/file)]
    (vec (sort-by :mtime >
                  (concat (rows-for :claude claude-root)
                          (rows-for :pi     pi-root))))))
