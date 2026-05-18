(ns options-trader.tui.claude-proc-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.tui.claude-proc :as cp]))

(deftest spawn-claude-returns-required-keys
  (let [result (cp/spawn-claude {})]
    (is (map? result))
    (is (contains? result :cmd))
    (is (contains? result :env))
    (is (contains? result :cwd))))

(deftest spawn-claude-cmd-is-vector-starting-with-claude
  (let [{:keys [cmd]} (cp/spawn-claude {})]
    (is (vector? cmd))
    (is (= "claude" (first cmd)))))

(deftest spawn-claude-includes-model-flag
  (let [{:keys [cmd]} (cp/spawn-claude {:model "claude-opus-4-7"})]
    (is (some #{"--model"} cmd))
    (is (some #{"claude-opus-4-7"} cmd))))

(deftest spawn-claude-includes-system-prompt-flag
  (let [{:keys [cmd]} (cp/spawn-claude {:system-prompt "You are helpful."})]
    (is (some #{"--system-prompt"} cmd))
    (is (some #{"You are helpful."} cmd))))

(deftest spawn-claude-env-is-map
  (let [{:keys [env]} (cp/spawn-claude {:env {"FOO" "bar"}})]
    (is (map? env))
    (is (= "bar" (get env "FOO")))))

(deftest spawn-claude-cwd-defaults-to-dot
  (let [{:keys [cwd]} (cp/spawn-claude {})]
    (is (= "." cwd))))

(deftest spawn-claude-cwd-custom
  (let [{:keys [cwd]} (cp/spawn-claude {:cwd "/tmp/test"})]
    (is (= "/tmp/test" cwd))))

(deftest ask-calls-spawn-fn-once
  (let [calls     (atom [])
        mock-fn   (fn [spec] (swap! calls conj spec) {:stdout "ok" :exit 0})
        _result   (cp/ask {:spawn-fn mock-fn :params {} :message "hello"})]
    (is (= 1 (count @calls)))))

(deftest ask-passes-message-as-input
  (let [calls   (atom [])
        mock-fn (fn [spec] (swap! calls conj spec) {:stdout "" :exit 0})]
    (cp/ask {:spawn-fn mock-fn :params {} :message "my question"})
    (is (= "my question" (:input (first @calls))))))

(deftest ask-returns-spawn-fn-result
  (let [mock-fn (fn [_] {:stdout "answer" :exit 0})
        result  (cp/ask {:spawn-fn mock-fn :params {} :message "q"})]
    (is (= "answer" (:stdout result)))
    (is (= 0 (:exit result)))))

(deftest ask-spawn-fn-receives-cmd-env-cwd
  (let [calls   (atom [])
        mock-fn (fn [spec] (swap! calls conj spec) {:stdout "" :exit 0})]
    (cp/ask {:spawn-fn mock-fn :params {:env {"K" "V"}} :message "x"})
    (let [spec (first @calls)]
      (is (contains? spec :cmd))
      (is (contains? spec :env))
      (is (contains? spec :cwd)))))

(deftest spawn-claude-omits-resume-by-default
  (testing "turn 1 spawns without --resume so claude starts a fresh session"
    (let [{:keys [cmd]} (cp/spawn-claude {})]
      (is (not-any? #{"--resume"} cmd)))))

(deftest spawn-claude-includes-resume-when-session-id-supplied
  (testing "turn 2+ spawns with --resume <session-id> to continue the prior conversation"
    (let [{:keys [cmd]} (cp/spawn-claude {:session-id "sess-abc-123"})
          tail          (drop-while #(not= "--resume" %) cmd)]
      (is (some #{"--resume"} cmd))
      (is (= "sess-abc-123" (second tail))
          "--resume is immediately followed by the session id"))))

(deftest ask-propagates-session-id-from-params
  (let [calls   (atom [])
        mock-fn (fn [spec] (swap! calls conj spec) {:stdout "" :exit 0})]
    (cp/ask {:spawn-fn mock-fn
             :params   {:session-id "sess-xyz"}
             :message  "follow-up"})
    (let [spec (first @calls)]
      (is (some #{"--resume"} (:cmd spec)))
      (is (some #{"sess-xyz"} (:cmd spec))))))

;;; ── Stream-json parsers ────────────────────────────────────────────────────

(def ^:private sample-jsonl
  (str
   "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"abc-123\"}\n"
   "{\"type\":\"assistant\",\"session_id\":\"abc-123\",\"message\":{\"content\":\"hi\"}}\n"
   "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"abc-123\","
     "\"usage\":{\"input_tokens\":1234,\"output_tokens\":56,"
                 "\"cache_read_input_tokens\":100,"
                 "\"cache_creation_input_tokens\":50}}\n"))

(deftest extract-session-id-returns-first-session-id-seen
  (is (= "abc-123" (cp/extract-session-id sample-jsonl))))

(deftest extract-session-id-handles-missing-events
  (is (nil? (cp/extract-session-id "")))
  (is (nil? (cp/extract-session-id "garbage non-json text"))))

(deftest extract-usage-pulls-from-result-event
  (let [u (cp/extract-usage sample-jsonl)]
    (is (= 1234 (:input-tokens u)))
    (is (= 56   (:output-tokens u)))
    (is (= 100  (:cache-read-input-tokens u)))
    (is (= 50   (:cache-creation-input-tokens u)))))

(deftest extract-usage-is-nil-when-no-result-event
  (is (nil? (cp/extract-usage
              "{\"type\":\"system\",\"session_id\":\"abc\"}\n"))))

(deftest extractors-tolerate-non-json-lines-interspersed
  (let [mixed (str "preamble line\n" sample-jsonl "trailing garbage\n")]
    (is (= "abc-123" (cp/extract-session-id mixed)))
    (is (= 1234 (:input-tokens (cp/extract-usage mixed))))))
