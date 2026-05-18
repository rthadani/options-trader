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

(deftest clear-claude-session-removes-entry
  (conv/record-claude-session! "AAPL" "sess-1")
  (conv/clear-claude-session! "AAPL")
  (is (nil? (conv/current-claude-session "AAPL"))))

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
