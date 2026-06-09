(ns options-trader.tui.llm-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [options-trader.tui.llm :as llm]))


;; One :each fixture — a second use-fixtures call would replace this one rather
;; than compose. Restores the global model/provider atoms and isolates the
;; conversation store per test.
(use-fixtures :each
  (fn [f]
    (require 'options-trader.tui.conversation)
    ((resolve 'options-trader.tui.conversation/reset-claude-sessions!))
    (let [prior-model    (llm/current-model)
          prior-provider (llm/current-provider)]
      (try (f)
           (finally (llm/set-model! prior-model)
                    (llm/set-provider! prior-provider))))))

(deftest current-model-has-a-default
  (is (string? (llm/current-model)))
  (is (not (str/blank? (llm/current-model)))))

(deftest set-model!-updates-current-model
  (llm/set-model! "claude-haiku-4-5-20251001")
  (is (= "claude-haiku-4-5-20251001" (llm/current-model))))

(deftest set-model!-trims-whitespace
  (llm/set-model! "  claude-opus-4-5  ")
  (is (= "claude-opus-4-5" (llm/current-model))))

(deftest set-model!-rejects-blank
  (is (thrown? clojure.lang.ExceptionInfo (llm/set-model! "")))
  (is (thrown? clojure.lang.ExceptionInfo (llm/set-model! "   ")))
  (is (thrown? clojure.lang.ExceptionInfo (llm/set-model! nil))))


(deftest spawn-claude-uses-active-model-when-none-supplied
  (llm/set-model! "claude-test-model")
  (let [{:keys [cmd]} (llm/spawn-claude {})]
    (is (some #{"--model"} cmd))
    (is (some #{"claude-test-model"} cmd))))

(deftest spawn-claude-explicit-model-wins-over-registry
  (llm/set-model! "registry-model")
  (let [{:keys [cmd]} (llm/spawn-claude {:model "explicit-model"})]
    (is (some #{"explicit-model"} cmd))
    (is (not-any? #{"registry-model"} cmd))))

(deftest spawn-claude-threads-session-id
  (let [{:keys [cmd]} (llm/spawn-claude {:session-id "sess-1"})]
    (is (some #{"--resume"} cmd))
    (is (some #{"sess-1"} cmd))))

(deftest spawn-claude-omits-resume-when-no-session-id
  (let [{:keys [cmd]} (llm/spawn-claude {})]
    (is (not-any? #{"--resume"} cmd))))


(def ^:private sample-claude-stdout
  (str
   "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-from-claude\"}\n"
   "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"sess-from-claude\","
     "\"usage\":{\"input_tokens\":2000,\"output_tokens\":300}}\n"))

(deftest ask-in-scope-captures-session-id-on-first-turn
  (let [conv          (find-ns 'options-trader.tui.conversation)
        get-session   (ns-resolve conv 'current-claude-session)
        path          (str (System/getProperty "java.io.tmpdir")
                           "/ois-test-" (System/currentTimeMillis) ".edn")
        mock-spawn-fn (fn [_] {:stdout sample-claude-stdout :exit 0})
        result        (llm/ask-in-scope
                        {:scope-key    "AAPL"
                         :spawn-fn     mock-spawn-fn
                         :params       {}
                         :message      "hello"
                         :persist-path path})]
    (is (= "sess-from-claude" (:session-id result)))
    (is (= 2000 (-> result :usage :input-tokens)))
    (is (= "sess-from-claude"
           (:claude-session-id (get-session "AAPL"))))
    (clojure.java.io/delete-file path :silently)))

(deftest ask-in-scope-auto-resumes-on-second-turn
  (let [conv          (find-ns 'options-trader.tui.conversation)
        record!       (ns-resolve conv 'record-claude-session!)
        spawned       (atom [])
        mock-spawn-fn (fn [spec]
                        (swap! spawned conj spec)
                        {:stdout sample-claude-stdout :exit 0})
        path          (str (System/getProperty "java.io.tmpdir")
                           "/ois-test-" (System/currentTimeMillis) ".edn")]
    (record! "AAPL" "sess-prior")
    (llm/ask-in-scope {:scope-key    "AAPL"
                       :spawn-fn     mock-spawn-fn
                       :params       {}
                       :message      "follow-up"
                       :persist-path path})
    (let [cmd (:cmd (first @spawned))]
      (is (some #{"--resume"} cmd))
      (is (some #{"sess-prior"} cmd)))
    (clojure.java.io/delete-file path :silently)))

(deftest ask-in-scope-accumulates-token-usage-across-turns
  (let [conv          (find-ns 'options-trader.tui.conversation)
        get-session   (ns-resolve conv 'current-claude-session)
        mock-spawn-fn (fn [_] {:stdout sample-claude-stdout :exit 0})
        path          (str (System/getProperty "java.io.tmpdir")
                           "/ois-test-" (System/currentTimeMillis) ".edn")]
    (llm/ask-in-scope {:scope-key "MSFT" :spawn-fn mock-spawn-fn :params {}
                       :message "t1" :persist-path path})
    (llm/ask-in-scope {:scope-key "MSFT" :spawn-fn mock-spawn-fn :params {}
                       :message "t2" :persist-path path})
    (let [s (get-session "MSFT")]
      (is (= 4000 (:tokens-input  s)))
      (is (= 600  (:tokens-output s)))
      (is (= 2    (:turn-count s))))
    (clojure.java.io/delete-file path :silently)))


(deftest complete-shells-out-to-claude-print
  (let [captured (atom nil)
        fake-sh  (fn [& args]
                   (reset! captured args)
                   {:exit 0 :out "  hello world  \n"})]
    (is (= "hello world" (llm/complete {:sh-fn fake-sh} "ping")))
    (let [args @captured]
      (is (= "claude" (first args)))
      (is (some #{"--print"} args))
      (is (some #{"--dangerously-skip-permissions"} args)))))

(deftest complete-uses-active-model-by-default
  (llm/set-model! "claude-haiku-4-5-20251001")
  (let [captured (atom nil)
        fake-sh  (fn [& args] (reset! captured args) {:exit 0 :out "ok"})]
    (llm/complete {:sh-fn fake-sh} "ping")
    (is (some #{"claude-haiku-4-5-20251001"} @captured))))

(deftest complete-explicit-model-wins-over-registry
  (llm/set-model! "registry-model")
  (let [captured (atom nil)
        fake-sh  (fn [& args] (reset! captured args) {:exit 0 :out "ok"})]
    (llm/complete {:model "kimi-k2.5" :sh-fn fake-sh} "ping")
    (is (some #{"kimi-k2.5"} @captured))
    (is (not-any? #{"registry-model"} @captured))))

(deftest complete-prepends-system-prompt
  (let [captured (atom nil)
        fake-sh  (fn [& args] (reset! captured args) {:exit 0 :out "ok"})]
    (llm/complete {:system "You are terse." :sh-fn fake-sh} "say hi")
    (let [opts (apply hash-map (drop-while string? @captured))
          full (:in opts)]
      (is (str/includes? full "You are terse."))
      (is (str/includes? full "say hi")))))

(deftest complete-throws-exhausted-on-rate-limit
  (let [fake-sh (fn [& _] {:exit 1 :out "" :err "rate_limit_error: try again"})]
    (try
      (llm/complete {:sh-fn fake-sh} "ping")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :exhausted (:cause (ex-data e))))))))

(deftest complete-throws-generic-on-non-rate-limit-failure
  (let [fake-sh (fn [& _] {:exit 1 :out "" :err "weird crash"})]
    (try
      (llm/complete {:sh-fn fake-sh} "ping")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (not= :exhausted (:cause (ex-data e))))))))

(deftest complete-json-parses-keyword-keyed-map
  (let [fake-sh (fn [& _] {:exit 0 :out "{\"action\":\"hold\",\"confidence\":0.7}"})]
    (let [r (llm/complete-json {:sh-fn fake-sh} "decide")]
      (is (= "hold" (:action r)))
      (is (= 0.7 (:confidence r))))))

(deftest complete-json-strips-markdown-fences
  (let [fake-sh (fn [& _] {:exit 0 :out "```json\n{\"ok\":true}\n```"})]
    (is (true? (:ok (llm/complete-json {:sh-fn fake-sh} "x"))))))

(deftest complete-json-throws-on-non-json
  (let [fake-sh (fn [& _] {:exit 0 :out "totally not json"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (llm/complete-json {:sh-fn fake-sh} "x")))))

(deftest rate-limited-detects-quota-signal
  (is (true?  (llm/rate-limited? "Claude usage limit reached. Tokens will renew at 5pm.")))
  (is (false? (llm/rate-limited? "all good"))))


(deftest set-provider!-validates-and-updates
  (is (= :kimi   (llm/set-provider! "kimi")))
  (is (= :kimi   (llm/current-provider)))
  (is (= :ollama (llm/set-provider! :ollama)))
  (is (thrown? clojure.lang.ExceptionInfo (llm/set-provider! "bogus")))
  (is (thrown? clojure.lang.ExceptionInfo (llm/set-provider! nil))))

(deftest complete-ollama-shells-out-to-ollama-launch-claude
  (let [captured (atom nil)
        fake-sh  (fn [& args] (reset! captured args) {:exit 0 :out "ok"})]
    (llm/complete {:provider :ollama :model "qwen2.5-coder" :sh-fn fake-sh} "ping")
    (let [args @captured]
      (is (= ["ollama" "launch" "claude"] (take 3 args)))
      (is (some #{"qwen2.5-coder"} args))
      (is (some #{"--print"} args)))))

(deftest complete-explicit-provider-overrides-active
  (llm/set-provider! :claude)
  (let [captured (atom nil)
        fake-sh  (fn [& args] (reset! captured args) {:exit 0 :out "ok"})]
    (llm/complete {:provider :ollama :model "m" :sh-fn fake-sh} "x")
    (is (= "ollama" (first @captured)))))

(deftest complete-routes-by-active-provider
  (llm/set-provider! :ollama)
  (let [captured (atom nil)
        fake-sh  (fn [& args] (reset! captured args) {:exit 0 :out "ok"})]
    (llm/complete {:model "m" :sh-fn fake-sh} "x")
    (is (= "ollama" (first @captured)))))
