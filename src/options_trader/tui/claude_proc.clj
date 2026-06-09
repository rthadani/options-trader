(ns options-trader.tui.claude-proc
  (:require [cheshire.core         :as json]
            [clojure.string        :as str]
            [options-trader.tui.proc :as proc]
            [options-trader.util   :as util]))

(def ^:private default-model "claude-opus-4-5")

(defn spawn-claude
  "Return a spawn-params map {:cmd :env :cwd} without launching any process.
   Callers pass the map to ProcessBuilder or to an injectable spawn-fn.

   When :session-id is supplied, --resume is appended so the spawned CLI
   continues the prior conversation rather than starting fresh. Session-ids
   are surfaced by claude's stream-json output on the first turn; persist
   them in tui.conversation and feed them back here for turn 2+."
  [{:keys [model system-prompt cwd env session-id]
    :or   {model default-model cwd "."}}]
  {:cmd (cond-> ["claude"]
          model         (conj "--model" model)
          system-prompt (conj "--system-prompt" system-prompt)
          session-id    (conj "--resume" session-id))
   :env (or env {})
   :cwd cwd})

(defn ask
  "Send message to the Claude CLI via spawn-fn.
   spawn-fn receives {:cmd :env :cwd :input} and returns {:stdout :exit}.
   Uses injectable spawn-fn so tests can mock the subprocess."
  [{:keys [spawn-fn params message]}]
  (let [spec (spawn-claude (or params {}))]
    (spawn-fn (assoc spec :input (or message "")))))


(defn- parse-jsonl-events
  "Parse the stream-json stdout into a seq of event maps, dropping any line
   that isn't valid JSON. Tolerant — claude prefixes/suffixes outside the
   JSON-lines protocol are ignored."
  [stdout]
  (->> (str/split-lines (or stdout ""))
       (keep util/safe-json-parse)))

(defn extract-session-id
  "Return claude's session-id from the stream-json stdout, or nil if absent.
   Stable across all events of a single turn — picks the first one seen."
  [stdout]
  (->> (parse-jsonl-events stdout)
       (keep :session_id)
       first))

(defn extract-usage
  "Return {:input-tokens N :output-tokens N :cache-read-input-tokens N
   :cache-creation-input-tokens N} from the result event of a stream-json
   transcript, or nil if no result event was emitted (turn errored / aborted)."
  [stdout]
  (some-> (parse-jsonl-events stdout)
          (->> (filter #(= "result" (:type %)))
               first)
          :usage
          (as-> u
            {:input-tokens                (or (:input_tokens u) 0)
             :output-tokens               (or (:output_tokens u) 0)
             :cache-read-input-tokens     (or (:cache_read_input_tokens u) 0)
             :cache-creation-input-tokens (or (:cache_creation_input_tokens u) 0)})))


