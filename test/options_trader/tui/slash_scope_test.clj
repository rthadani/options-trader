(ns options-trader.tui.slash-scope-test
  "Tests for the scope/claude-session slash commands: /investigate,
   /clear-investigation, /reset, /sessions, /compact."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [options-trader.tui.slash :as sl]
            [options-trader.tui.conversation :as conv]))

(defn- isolate [t]
  (conv/reset-claude-sessions!)
  (t))

(use-fixtures :each isolate)

(defn- tmp-path []
  (str (System/getProperty "java.io.tmpdir")
       "/scope-slash-test-" (System/currentTimeMillis) ".edn"))

;;; ── /investigate ────────────────────────────────────────────────────────────

(deftest investigate-sets-scope-to-symbol
  (let [r (sl/dispatch {} "/investigate AAPL")]
    (is (= :ok           (:status r)))
    (is (= :set-scope    (:command r)))
    (is (= "AAPL"        (:scope-key r)))
    (is (false?          (:resumed? r))
        "no prior session → not resumed")))

(deftest investigate-uppercases-symbol
  (let [r (sl/dispatch {} "/investigate aapl")]
    (is (= "AAPL" (:scope-key r)))))

(deftest investigate-flags-resumed-when-prior-session-exists
  (conv/record-claude-session! "AAPL" "sess-prior")
  (let [r (sl/dispatch {} "/investigate AAPL")]
    (is (:resumed? r))))

(deftest investigate-with-no-symbol-returns-error-command
  (let [r (sl/dispatch {} "/investigate")]
    (is (= :ok               (:status r)))
    (is (= :set-scope-error  (:command r)))
    (is (string? (:error r)))))

;;; ── /clear-investigation ────────────────────────────────────────────────────

(deftest clear-investigation-switches-to-scratch
  (let [r (sl/dispatch {} "/clear-investigation")]
    (is (= :set-scope (:command r)))
    (is (= :scratch   (:scope-key r)))))

;;; ── /reset ──────────────────────────────────────────────────────────────────

(deftest reset-drops-current-scope-claude-session
  (conv/record-claude-session! "AAPL" "sess-aapl")
  (let [path (tmp-path)
        r    (sl/dispatch {:scope-key "AAPL" :persist-path path}
                          "/reset")]
    (is (= :reset-scope     (:command r)))
    (is (= "AAPL"           (:scope-key r)))
    (is (= "sess-aapl"      (:prior-session-id r)))
    (is (nil? (conv/current-claude-session "AAPL"))
        "the session-id binding for AAPL is gone")
    (io/delete-file path :silently)))

(deftest reset-does-not-affect-other-scopes
  (conv/record-claude-session! "AAPL" "sess-aapl")
  (conv/record-claude-session! "MSFT" "sess-msft")
  (let [path (tmp-path)]
    (sl/dispatch {:scope-key "AAPL" :persist-path path} "/reset")
    (is (= "sess-msft"
           (:claude-session-id (conv/current-claude-session "MSFT"))))
    (io/delete-file path :silently)))

;;; ── /sessions ───────────────────────────────────────────────────────────────

(deftest sessions-lists-known-scopes-with-thresholds
  (conv/record-claude-session! "AAPL" "sess-1")
  (conv/record-turn-stats! "AAPL" {:input-tokens 1000 :output-tokens 100})
  (let [r (sl/dispatch {} "/sessions")]
    (is (= :list-sessions (:command r)))
    (is (vector? (:rows r)))
    (let [row (first (:rows r))]
      (is (= "AAPL"   (:scope-key row)))
      (is (= "sess-1" (:claude-session-id row)))
      (is (= 1000     (:tokens-input row)))
      (is (= :ok      (:threshold-state row))))))

(deftest sessions-flags-soft-and-hard-thresholds
  (conv/record-turn-stats! "AAPL" {:input-tokens 85000})  ; soft
  (conv/record-turn-stats! "MSFT" {:input-tokens 200000}) ; hard
  (let [rows (-> (sl/dispatch {} "/sessions") :rows)
        by-sym (into {} (map (juxt :scope-key :threshold-state) rows))]
    (is (= :soft (get by-sym "AAPL")))
    (is (= :hard (get by-sym "MSFT")))))

;;; ── /compact ────────────────────────────────────────────────────────────────

(deftest compact-emits-intent-with-prior-token-count
  (conv/record-claude-session! "AAPL" "sess-1")
  (conv/record-turn-stats! "AAPL" {:input-tokens 90000 :output-tokens 1000})
  (let [r (sl/dispatch {:scope-key "AAPL"} "/compact")]
    (is (= :compact-scope (:command r)))
    (is (= "AAPL"         (:scope-key r)))
    (is (= 90000          (:prior-tokens r)))
    (is (= :soft          (:threshold r)))
    (is (true?            (:has-session? r)))))

(deftest compact-on-empty-scope-still-returns-intent
  (let [r (sl/dispatch {:scope-key :scratch} "/compact")]
    (is (= :compact-scope (:command r)))
    (is (= 0              (:prior-tokens r)))
    (is (false?           (:has-session? r))
        "TUI driver uses :has-session? to decide whether to even invoke claude")))

;;; ── Smoke: all new commands are recognised ──────────────────────────────────

(deftest new-commands-in-required-set
  (doseq [cmd ["/reset" "/sessions" "/compact"]]
    (is (contains? sl/required-commands cmd))))
