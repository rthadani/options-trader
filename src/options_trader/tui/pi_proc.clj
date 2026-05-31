(ns options-trader.tui.pi-proc
  "Pi agent subprocess handling. Mirrors claude-proc but targets the pi CLI
   (`pi -p --mode json`) instead of claude."
  (:require [cheshire.core   :as json]
            [clojure.string  :as str]))

(defn spawn-pi
  "Return a spawn-params map {:cmd :env :cwd} for the pi CLI.
   Uses `pi -p --mode json` for streaming TUI sessions.
   :provider is forwarded as `--provider`; :model as `--model`;
   :system-prompt is forwarded as `--system-prompt`;
   :additional-dirs is forwarded as repeated `--skill` flags (pi's closest
   equivalent to `--add-dir`)."
  [{:keys [model provider system-prompt cwd additional-dirs]
    :or   {cwd "."}}]
  {:cmd (cond-> ["pi" "-p" "--mode" "json"]
          provider      (conj "--provider" (name provider))
          model         (conj "--model" model)
          system-prompt (conj "--system-prompt" system-prompt)
          true          (into (mapcat #(vector "--skill" %) additional-dirs)))
   :env {}
   :cwd cwd})

(defn ask
  "Send message to the pi CLI via spawn-fn.
   spawn-fn receives {:cmd :env :cwd :input} and returns {:stdout :exit}."
  [{:keys [spawn-fn params message]}]
  (let [spec (spawn-pi (or params {}))]
    (spawn-fn (assoc spec :input (or message "")))))

;;; ── Stream-json parsing (pi --mode json) ──────────────────────────────────

(defn- parse-jsonl-events
  [stdout]
  (->> (str/split-lines (or stdout ""))
       (keep (fn [line]
               (try (json/parse-string line true)
                    (catch Exception _ nil))))))

(defn agent-end-line?
  "Returns true if the line is pi's terminal agent_end event."
  [line]
  (try
    (= "agent_end" (:type (json/parse-string line true)))
    (catch Exception _ false)))

(defn parse-event
  "Parse a single pi JSON line into a normalized map for the TUI.
   Returns nil for unparseable or irrelevant lines.

   Normalized event types:
     {:type :text     :text <string>}        — assistant text delta
     {:type :thinking :text <string>}        — reasoning/thinking delta
     {:type :tool     :name :args :start?}   — tool start (start? true)
     {:type :tool     :name :error? :end?}   — tool end   (end? true)
     {:type :usage    :input-tokens :output-tokens :cost-usd}
     {:type :done}                             — agent_end, run is complete"
  [line]
  (try
    (let [ev (json/parse-string line true)]
      (case (:type ev)
        "message_update"
        (let [ame (:assistantMessageEvent ev)]
          (case (:type ame)
            "text_delta"     {:type :text     :text (:delta ame)}
            "thinking_delta" {:type :thinking :text (:delta ame)}
            nil))

        "tool_execution_start"
        {:type :tool :name (:toolName ev) :args (:args ev) :start? true}

        "tool_execution_end"
        {:type :tool :name (:toolName ev) :error? (:isError ev) :end? true}

        "message_end"
        (let [u (get-in ev [:message :usage])]
          (when u
            {:type :usage
             :input-tokens  (:input u)
             :output-tokens (:output u)
             :cost-usd      (get-in u [:cost :total])}))

        "agent_end"
        {:type :done}

        nil))
    (catch Exception _ nil)))

(defn make-process-spawn-fn
  "Return a real spawn-fn that invokes the pi CLI via ProcessBuilder.
   Consumes {:cmd :env :cwd :input}, returns {:stdout :exit}."
  []
  (fn [{:keys [cmd env cwd input]}]
    (let [pb  (ProcessBuilder. ^java.util.List cmd)
          _   (.directory pb (java.io.File. ^String cwd))
          env-map (.environment pb)]
      (doseq [[k v] env]
        (.put env-map k v))
      (.redirectErrorStream pb true)
      (let [proc   (.start pb)
            writer (java.io.PrintWriter. (.getOutputStream proc) true)]
        (.println writer input)
        (.close writer)
        (let [out (slurp (.getInputStream proc))
              rc  (.waitFor proc)]
          {:stdout out :exit rc})))))
