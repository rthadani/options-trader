(ns options-trader.tui.llm
  (:require [cheshire.core                   :as json]
            [clojure.java.shell              :as shell]
            [clojure.string                  :as str]
            [options-trader.tui.claude-proc  :as cp]
            [options-trader.tui.conversation :as conv]))

(def default-model "claude-opus-4-5")

(defonce ^:private active-model (atom default-model))

(defn current-model [] @active-model)

(defn set-model! [m]
  (let [s (some-> m str str/trim)]
    (when (str/blank? s)
      (throw (ex-info "model id must be non-blank" {:given m})))
    (reset! active-model s)
    s))

(defn spawn-claude
  [{:keys [model system-prompt cwd session-id]
    :or   {cwd "."}}]
  (cp/spawn-claude {:model         (or model @active-model)
                    :system-prompt system-prompt
                    :cwd           cwd
                    :env           {}
                    :session-id    session-id}))

(defn ask
  [{:keys [spawn-fn params message]}]
  (let [params (cond-> (or params {})
                 (nil? (:model params)) (assoc :model @active-model))]
    (cp/ask {:spawn-fn spawn-fn :params params :message message})))

(defn ask-in-scope
  [{:keys [scope-key spawn-fn params message persist-path]}]
  (let [prior  (conv/current-claude-session scope-key)
        params (cond-> (or params {})
                 (:claude-session-id prior)
                 (assoc :session-id (:claude-session-id prior)))
        result (ask {:spawn-fn spawn-fn :params params :message message})
        out    (:stdout result)
        sid    (cp/extract-session-id out)
        usage  (cp/extract-usage out)]
    (when sid   (conv/record-claude-session! scope-key sid))
    (when usage (conv/record-turn-stats!     scope-key usage))
    (when (or sid usage)
      (conv/persist-claude-sessions! (or persist-path conv/default-persist-path)))
    (assoc result :session-id sid :usage usage :scope-key scope-key)))

;;; ── Synchronous one-shot delegation (`claude --print`) ─────────────────────

(def rate-limit-signals
  #{"rate_limit_error" "overloaded_error" "429" "too many requests"
    "rate limit" "tokens will renew" "usage limit" "will reset at"
    "claude usage limit reached" "you have reached your"})

(defn rate-limited? [text]
  (let [out (str/lower-case (str text))]
    (boolean (some #(str/includes? out %) rate-limit-signals))))

(defn exhaustion-message [text]
  (let [lines (str/split-lines (str text))
        match (->> lines
                   (filter #(let [l (str/lower-case %)]
                              (some (fn [s] (str/includes? l s)) rate-limit-signals)))
                   first)]
    (or (some-> match str/trim not-empty)
        "Claude reported a rate limit / quota error.")))

(defn- throw-cli-error! [tag {:keys [exit out err]}]
  (let [combined (str out "\n" err)]
    (if (rate-limited? combined)
      (throw (ex-info (str tag " (rate limited)")
                      {:cause :exhausted :message (exhaustion-message combined)
                       :exit exit :stderr err}))
      (throw (ex-info tag {:exit exit :stderr err})))))

(defn complete
  [{:keys [model system sh-fn] :or {sh-fn shell/sh}} prompt]
  (let [m    (or model @active-model)
        full (if (seq system) (str system "\n\n---\n\n" prompt) prompt)
        args (cond-> ["claude" "--print" "--dangerously-skip-permissions"]
               m (concat ["--model" m]))
        res  (apply sh-fn (concat args [:in full]))]
    (when (pos? (:exit res))
      (throw-cli-error! "claude CLI error" res))
    (str/trim (:out res))))

(defn- extract-json-object [text]
  (let [start (.indexOf ^String text "{")
        end   (.lastIndexOf ^String text "}")]
    (if (and (>= start 0) (> end start))
      (subs text start (inc end))
      text)))

(defn complete-json [opts prompt]
  (let [raw      (complete opts (str prompt
                                     "\n\nRespond with valid JSON only. "
                                     "No preamble, no markdown fences."))
        json-str (-> raw
                     (str/replace #"(?s)```[a-z]*\n?" "")
                     (str/replace #"```" "")
                     str/trim
                     extract-json-object)]
    (try
      (json/parse-string json-str true)
      (catch Exception e
        (throw (ex-info (str "complete-json got non-JSON: "
                             (subs raw 0 (min 200 (count raw))))
                        {:raw raw :cause e}))))))
