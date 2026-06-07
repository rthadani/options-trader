(ns options-trader.tui.conversation-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [options-trader.tui.conversation :as conv]))

(defn- isolate-state [t]
  (conv/reset-all!)
  (conv/reset-claude-sessions!)
  (t))

(use-fixtures :each isolate-state)

;;; ── Scope → claude-session store ────────────────────────────────────────────

(deftest record-claude-session-stores-id-keyed-by-scope
  (conv/record-claude-session! "AAPL" "sess-aapl-1")
  (is (= "sess-aapl-1"
         (:claude-session-id (conv/current-claude-session "AAPL")))))

(deftest scope-store-isolates-per-symbol
  (conv/record-claude-session! "AAPL" "sess-aapl")
  (conv/record-claude-session! "MSFT" "sess-msft")
  (is (= "sess-aapl" (:claude-session-id (conv/current-claude-session "AAPL"))))
  (is (= "sess-msft" (:claude-session-id (conv/current-claude-session "MSFT")))))

(deftest record-claude-session-is-idempotent-on-same-id
  (conv/record-claude-session! "AAPL" "sess-aapl-1")
  (conv/record-claude-session! "AAPL" "sess-aapl-1")
  (is (= "sess-aapl-1"
         (:claude-session-id (conv/current-claude-session "AAPL")))))

(deftest record-claude-session-preserves-token-counters
  (conv/record-claude-session! "AAPL" "sess-1")
  (conv/record-turn-stats! "AAPL" {:input-tokens 100 :output-tokens 50})
  (conv/record-claude-session! "AAPL" "sess-1")
  (is (= 100 (:tokens-input  (conv/current-claude-session "AAPL"))))
  (is (= 50  (:tokens-output (conv/current-claude-session "AAPL")))))

(deftest record-turn-stats-accumulates
  (conv/record-turn-stats! "AAPL" {:input-tokens 100 :output-tokens 50})
  (conv/record-turn-stats! "AAPL" {:input-tokens 200 :output-tokens 75})
  (let [s (conv/current-claude-session "AAPL")]
    (is (= 300 (:tokens-input s)))
    (is (= 125 (:tokens-output s)))
    (is (= 2   (:turn-count s)))))

(deftest clear-claude-session-removes-claude-id-only
  (conv/record-claude-session! "AAPL" "sess-1")
  (conv/clear-claude-session! "AAPL")
  (is (nil? (:claude-session-id (conv/current-claude-session "AAPL")))
      "claude-session-id is dropped"))

(deftest clear-claude-session-leaves-pi-session-intact
  (conv/record-claude-session! "AAPL" "claude-sess")
  (conv/record-pi-session!     "AAPL" "pi-sess")
  (conv/clear-claude-session!  "AAPL")
  (let [s (conv/current-pi-session "AAPL")]
    (is (nil? (:claude-session-id s)))
    (is (= "pi-sess" (:pi-session-id s)))))

(deftest ensure-pi-session-id-creates-then-reuses
  (let [id1 (conv/ensure-pi-session-id! "MSFT")
        id2 (conv/ensure-pi-session-id! "MSFT")]
    (is (string? id1))
    (is (= id1 id2) "second call returns the same id")))

(deftest clear-pi-session-leaves-claude-intact
  (conv/record-claude-session! "AAPL" "claude-sess")
  (conv/record-pi-session!     "AAPL" "pi-sess")
  (conv/clear-pi-session!      "AAPL")
  (let [s (conv/current-claude-session "AAPL")]
    (is (= "claude-sess" (:claude-session-id s)))
    (is (nil? (:pi-session-id s)))))

(deftest scratch-scope-is-just-another-key
  (conv/record-claude-session! :scratch "sess-scratch")
  (is (= "sess-scratch"
         (:claude-session-id (conv/current-claude-session :scratch))))
  (is (nil? (conv/current-claude-session "AAPL"))
      "tickers and :scratch live in independent buckets"))

(deftest list-claude-sessions-orders-by-recency
  (conv/record-claude-session! "AAPL" "sess-a")
  (Thread/sleep 5)
  (conv/record-claude-session! "MSFT" "sess-m")
  (Thread/sleep 5)
  (conv/record-claude-session! "AAPL" "sess-a")  ; refresh
  (let [rows (conv/list-claude-sessions)]
    (is (= 2 (count rows)))
    (is (= "AAPL" (:scope-key (first rows)))
        "most-recently-updated scope sorts first")))

;;; ── Token thresholds ────────────────────────────────────────────────────────

(deftest threshold-state-defaults
  (testing "below soft threshold → :ok"
    (conv/record-turn-stats! "AAPL" {:input-tokens 1000})
    (is (= :ok (conv/threshold-state "AAPL"))))
  (testing "above soft → :soft"
    (conv/record-turn-stats! "AAPL" {:input-tokens 80000})
    (is (= :soft (conv/threshold-state "AAPL"))))
  (testing "above hard → :hard"
    (conv/record-turn-stats! "AAPL" {:input-tokens 80000})  ; now 161K total
    (is (= :hard (conv/threshold-state "AAPL")))))

(deftest threshold-state-accepts-custom-thresholds
  (conv/record-turn-stats! "AAPL" {:input-tokens 500})
  (is (= :ok    (conv/threshold-state "AAPL" {:soft 1000 :hard 2000})))
  (is (= :soft  (conv/threshold-state "AAPL" {:soft 100  :hard 1000})))
  (is (= :hard  (conv/threshold-state "AAPL" {:soft 100  :hard 400}))))

;;; ── Persistence ─────────────────────────────────────────────────────────────

(deftest persist-and-load-round-trips
  (let [path (str (System/getProperty "java.io.tmpdir")
                  "/options-trader-test-"
                  (System/currentTimeMillis)
                  ".edn")]
    (try
      (conv/record-claude-session! "AAPL" "sess-aapl")
      (conv/record-turn-stats! "AAPL" {:input-tokens 1000 :output-tokens 500})
      (conv/persist-claude-sessions! path)
      (conv/reset-claude-sessions!)
      (is (nil? (conv/current-claude-session "AAPL"))
          "in-memory state is cleared")
      (conv/load-claude-sessions! path)
      (let [s (conv/current-claude-session "AAPL")]
        (is (= "sess-aapl" (:claude-session-id s)))
        (is (= 1000        (:tokens-input s)))
        (is (= 500         (:tokens-output s))))
      (finally
        (io/delete-file path :silently)))))

(deftest load-missing-file-is-a-noop
  (conv/load-claude-sessions! "/tmp/definitely-not-a-real-file.edn")
  (is (= [] (conv/list-claude-sessions))
      "loading from a missing path leaves the store empty, not crashed"))

(defn- write-jsonl [^java.io.File f lines]
  (.mkdirs (.getParentFile f))
  (spit f (str (clojure.string/join "\n" lines) "\n")))

(deftest replay-turns-claude-keeps-text-skips-noise
  (let [f (java.io.File. (System/getProperty "java.io.tmpdir")
                         (str "ot-claude-" (System/currentTimeMillis) ".jsonl"))]
    (try
      (spit f (clojure.string/join "\n"
                ["{\"type\":\"queue-operation\",\"operation\":\"enqueue\"}"
                 "{\"type\":\"system\",\"subtype\":\"init\"}"
                 "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"first question\"}}"
                 "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"first answer\"},{\"type\":\"tool_use\",\"name\":\"x\"}]}}"
                 "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"x\"}]}}"
                 "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"second answer\"}]}}"
                 "{\"type\":\"result\",\"usage\":{}}"]))
      (let [turns (conv/replay-turns :claude (.getAbsolutePath f))]
        (is (= 3 (count turns)) "tool_result user message and non-text blocks are dropped")
        (is (= [:user :assistant :assistant] (mapv :role turns)))
        (is (= ["first question" "first answer" "second answer"]
               (mapv :text turns))))
      (finally (.delete f)))))

(deftest replay-turns-pi-keeps-text-skips-thinking-and-toolresult
  (let [f (java.io.File. (System/getProperty "java.io.tmpdir")
                         (str "ot-pi-" (System/currentTimeMillis) ".jsonl"))]
    (try
      (spit f (clojure.string/join "\n"
                ["{\"type\":\"session\",\"id\":\"s1\"}"
                 "{\"type\":\"model_change\",\"provider\":\"minimax\"}"
                 "{\"type\":\"message\",\"message\":{\"role\":\"user\",\"content\":\"hi pi\"}}"
                 "{\"type\":\"message\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"hmm\"},{\"type\":\"text\",\"text\":\"hello back\"}]}}"
                 "{\"type\":\"message\",\"message\":{\"role\":\"toolResult\",\"content\":[{\"type\":\"text\",\"text\":\"ignored\"}]}}"]))
      (let [turns (conv/replay-turns :pi (.getAbsolutePath f))]
        (is (= 2 (count turns)) "session/model events and toolResult are dropped")
        (is (= [:user :assistant] (mapv :role turns)))
        (is (= ["hi pi" "hello back"] (mapv :text turns))
            "thinking blocks stripped; only :type \"text\" content survives"))
      (finally (.delete f)))))

(deftest replay-turns-missing-file-returns-empty
  (is (= [] (conv/replay-turns :claude "/tmp/definitely-not-here.jsonl"))))

(deftest list-all-transcripts-discovers-both-agents-and-derives-session-ids
  (let [root  (java.io.File. (System/getProperty "java.io.tmpdir")
                             (str "ot-sessions-" (System/currentTimeMillis)))
        c-dir (java.io.File. root "claude/projects/-some-project")
        p-dir (java.io.File. root "pi/sessions")
        c-id  "11111111-1111-1111-1111-111111111111"
        p-id  "22222222-2222-2222-2222-222222222222"]
    (try
      (write-jsonl (java.io.File. c-dir (str c-id ".jsonl"))
                   ["{\"type\":\"system\",\"meta\":{}}"
                    "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hello claude\"}}"])
      (write-jsonl (java.io.File. p-dir (str "2026-06-07T10-00-00Z_" p-id ".jsonl"))
                   ["{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello pi\"}]}"])
      ;; Subagent files must NOT appear in the listing.
      (write-jsonl (java.io.File. c-dir (str "subagents/agent-xyz.jsonl"))
                   ["{\"type\":\"user\",\"message\":{\"content\":\"nested helper\"}}"])
      (conv/record-claude-session! "AAPL" c-id)
      (let [rows (conv/list-all-transcripts
                   {:runtime-claude-dir (.getAbsolutePath (java.io.File. root "claude"))
                    :pi-session-dir     (.getAbsolutePath p-dir)})
            ids (set (map :session-id rows))]
        (is (= 2 (count rows)) "two top-level transcripts, subagent excluded")
        (is (contains? ids c-id) "claude session-id derived from filename")
        (is (contains? ids p-id) "pi session-id derived from <ts>_<uuid> filename")
        (let [c-row (first (filter #(= :claude (:agent %)) rows))
              p-row (first (filter #(= :pi     (:agent %)) rows))]
          (is (= "AAPL"     (:scope-key c-row))   "tracked scope shows up")
          (is (= "hello claude" (:preview c-row)) "first user msg extracted (claude)")
          (is (nil? (:scope-key p-row))           "untracked pi session is orphan")
          (is (= "hello pi" (:preview p-row))     "first user msg extracted (pi, array content)")))
      (finally
        (doseq [f (reverse (file-seq root))]
          (.delete f))))))
