(ns options-trader.tui.llm
  "LLM calls for the TUI. Mirrors morpheus.executor.llm: every provider shells
   out to the claude CLI; non-Anthropic providers just override env vars to
   point it at an Anthropic-compatible endpoint.
     :claude  (default) — api.anthropic.com (cached login / ANTHROPIC_API_KEY)
     :ollama            — `ollama launch claude` (auto-pulls model)
     :kimi              — api.moonshot.ai/anthropic   (reads MOONSHOT_API_KEY)
     :minimax           — api.minimax.chat/anthropic  (reads MINIMAX_API_KEY)
   complete/complete-json dispatch on :provider; the streaming spawn (ask /
   ask-in-scope) routes the same way via the spawned process's env."
  (:require [cheshire.core                   :as json]
            [clojure.java.shell              :as shell]
            [clojure.string                  :as str]
            [options-trader.tui.claude-proc  :as cp]
            [options-trader.tui.conversation :as conv]))

(def default-model "claude-opus-4-5")
(def default-provider :claude)

(def known-providers #{:claude :ollama :kimi :minimax})

(defonce ^:private active-model    (atom default-model))
(defonce ^:private active-provider (atom default-provider))

(defn current-model    [] @active-model)
(defn current-provider [] @active-provider)

(defn set-model! [m]
  (let [s (some-> m str str/trim)]
    (when (str/blank? s)
      (throw (ex-info "model id must be non-blank" {:given m})))
    (reset! active-model s)
    s))

(defn set-provider! [p]
  (let [k (some-> p name str/trim str/lower-case keyword)]
    (when-not (contains? known-providers k)
      (throw (ex-info "unknown provider" {:given p :known known-providers})))
    (reset! active-provider k)
    k))

;;; ── Anthropic-compatible env (non-Anthropic providers) ─────────────────────

(def ^:private provider-endpoints
  {:kimi    {:base-url "https://api.moonshot.ai/anthropic"  :key-env "MOONSHOT_API_KEY"}
   :minimax {:base-url "https://api.minimax.chat/anthropic" :key-env "MINIMAX_API_KEY"}})

(defn- require-key! [key-env provider]
  (let [k (System/getenv key-env)]
    (when (str/blank? k)
      (throw (ex-info (str key-env " env var not set") {:provider provider})))
    k))

(defn- anthropic-compat-env
  "Parent env plus the overrides that repoint the claude CLI at an
   Anthropic-compatible endpoint for `provider` running `model`."
  [provider model]
  (let [{:keys [base-url key-env]} (provider-endpoints provider)
        api-key (require-key! key-env provider)]
    (-> (into {} (System/getenv))
        (assoc "ANTHROPIC_BASE_URL"             base-url
               "ANTHROPIC_AUTH_TOKEN"           api-key
               "ANTHROPIC_API_KEY"              ""
               "ANTHROPIC_MODEL"                model
               "ANTHROPIC_DEFAULT_OPUS_MODEL"   model
               "ANTHROPIC_DEFAULT_SONNET_MODEL" model
               "ANTHROPIC_DEFAULT_HAIKU_MODEL"  model
               "CLAUDE_CODE_SUBAGENT_MODEL"     model
               "ENABLE_TOOL_SEARCH"             "false"))))

;;; ── Streaming spawn (the in-session agent) ─────────────────────────────────

(defn streaming-invocation
  "Build {:cmd :env} for a streaming `claude` run routed to `provider`.
   `claude-args` are the flags after the binary (NOT the binary or --model).
   For :ollama the model is handled by `ollama launch`; otherwise it's `claude
   --model`. :kimi/:minimax add the Anthropic-compatible env overrides."
  [{:keys [model provider claude-args]}]
  (let [m (str (or model @active-model))
        p (or provider @active-provider)]
    (case p
      :ollama  {:cmd (into ["ollama" "launch" "claude" "--model" m "--yes" "--"] claude-args)
                :env {}}
      :kimi    {:cmd (into ["claude" "--model" m] claude-args) :env (anthropic-compat-env :kimi m)}
      :minimax {:cmd (into ["claude" "--model" m] claude-args) :env (anthropic-compat-env :minimax m)}
      {:cmd (into ["claude" "--model" m] claude-args) :env {}})))

(defn spawn-claude
  [{:keys [model provider system-prompt cwd session-id]
    :or   {cwd "."}}]
  (let [m (or model @active-model)
        p (or provider @active-provider)]
    (cp/spawn-claude {:model         m
                      :system-prompt system-prompt
                      :cwd           cwd
                      :env           (if (contains? provider-endpoints p)
                                       (anthropic-compat-env p m)
                                       {})
                      :session-id    session-id})))

(defn ask
  [{:keys [spawn-fn params message]}]
  (let [params (cond-> (or params {})
                 (nil? (:model params))    (assoc :model @active-model)
                 (nil? (:provider params)) (assoc :provider @active-provider))]
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

;;; ── Rate-limit detection ───────────────────────────────────────────────────

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

;;; ── Synchronous one-shot delegation (`claude --print`) ─────────────────────

(defn- with-system [system prompt]
  (if (seq system) (str system "\n\n---\n\n" prompt) prompt))

(defn- complete-claude [{:keys [model system sh-fn] :or {sh-fn shell/sh}} prompt]
  (let [m    (or model @active-model)
        full (with-system system prompt)
        args (cond-> ["claude" "--print" "--dangerously-skip-permissions"]
               m (concat ["--model" m]))
        res  (apply sh-fn (concat args [:in full]))]
    (when (pos? (:exit res))
      (throw-cli-error! "claude CLI error" res))
    (str/trim (:out res))))

(defn- complete-ollama [{:keys [model system sh-fn] :or {sh-fn shell/sh}} prompt]
  (let [m (or model @active-model)]
    (when (str/blank? (str m))
      (throw (ex-info "model required for :ollama provider" {:provider :ollama})))
    (let [full (with-system system prompt)
          args ["ollama" "launch" "claude" "--model" (str m) "--yes"
                "--" "--print" "--dangerously-skip-permissions"]
          res  (apply sh-fn (concat args [:in full]))]
      (when (pos? (:exit res))
        (throw-cli-error! "ollama launch claude error" res))
      (str/trim (:out res)))))

(defn- complete-via-endpoint [provider {:keys [model system sh-fn] :or {sh-fn shell/sh}} prompt]
  (let [m    (or model @active-model)
        full (with-system system prompt)
        env  (anthropic-compat-env provider m)
        args (cond-> ["claude" "--print" "--dangerously-skip-permissions"]
               m (concat ["--model" m]))
        res  (apply sh-fn (concat args [:in full :env env]))]
    (when (pos? (:exit res))
      (throw-cli-error! (str "claude CLI error (" (name provider) ")") res))
    (str/trim (:out res))))

(defn complete
  "Run claude --print, optionally routed to a non-Anthropic backend. Dispatches
   on :provider — :ollama, :kimi, :minimax, or :claude (default). :model and
   :provider fall back to the active selection; :sh-fn is injectable for tests."
  [{:keys [provider] :as model-config} prompt]
  (case (or provider @active-provider)
    :ollama  (complete-ollama model-config prompt)
    :kimi    (complete-via-endpoint :kimi    model-config prompt)
    :minimax (complete-via-endpoint :minimax model-config prompt)
    (complete-claude model-config prompt)))

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
