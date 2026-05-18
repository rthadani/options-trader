(ns options-trader.tui.slash-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.tui.slash :as sl]))

(def all-commands
  ["/help" "/clear" "/history" "/resume" "/run"
   "/screens" "/universes" "/investigate" "/clear-investigation"
   "/note" "/findings" "/refresh" "/portfolio" "/watchlist"
   "/promote" "/demote" "/model" "/quit"])

(deftest all-required-commands-are-known
  (doseq [cmd all-commands]
    (is (sl/known-command? cmd) (str "unknown command: " cmd))))

(deftest dispatch-returns-ok-for-all-commands
  (doseq [cmd all-commands]
    (let [result (sl/dispatch {} cmd)]
      (is (= :ok (:status result)) (str "expected :ok for " cmd)))))

(deftest dispatch-unknown-command-returns-unknown-status
  (let [result (sl/dispatch {} "/nonexistent")]
    (is (= :unknown-command (:status result)))))

(deftest dispatch-strips-arguments-from-cmd
  (let [result (sl/dispatch {} "/run my-screen")]
    (is (= :ok (:status result)))))

(deftest dispatch-passes-args-to-handler
  (let [result (sl/dispatch {} "/investigate AAPL")]
    (is (= :ok (:status result)))
    (is (= ["AAPL"] (:args result)))))

(deftest dispatch-handles-extra-whitespace
  (let [result (sl/dispatch {} "  /help  ")]
    (is (= :ok (:status result)))))

(deftest required-commands-set-matches-dispatch-table
  (is (= sl/required-commands (set (keys sl/dispatch-table)))))

(deftest dispatch-table-has-all-required-commands
  (doseq [cmd all-commands]
    (is (contains? sl/dispatch-table cmd) (str "dispatch-table missing: " cmd))))

(deftest help-command-dispatch
  (is (= :ok (:status (sl/dispatch {} "/help")))))

(deftest quit-command-dispatch
  (is (= :ok (:status (sl/dispatch {} "/quit")))))

(deftest model-command-dispatch
  (is (= :ok (:status (sl/dispatch {} "/model claude-opus-4-7")))))

(deftest model-with-arg-sets-active-model
  (require '[options-trader.tui.llm :as llm])
  (let [prior ((resolve 'llm/current-model))]
    (try
      (let [result (sl/dispatch {} "/model claude-haiku-4-5-20251001")]
        (is (= :set-model (:command result)))
        (is (= "claude-haiku-4-5-20251001" (:model result)))
        (is (= "claude-haiku-4-5-20251001" ((resolve 'llm/current-model)))))
      (finally ((resolve 'llm/set-model!) prior)))))

(deftest model-without-arg-shows-current-model
  (let [result (sl/dispatch {} "/model")]
    (is (= :show-model (:command result)))
    (is (string? (:model result)))))
