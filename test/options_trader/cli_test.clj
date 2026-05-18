(ns options-trader.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.cli :as cli]))

(deftest dispatch-portfolio-test
  (testing "portfolio subcommand returns positions and account-summary"
    (let [result (cli/dispatch ["portfolio"])]
      (is (= :portfolio (:subcommand result)))
      (is (vector? (:positions result)))
      (is (map? (:account-summary result))))))

(deftest dispatch-screen-test
  (testing "screen subcommand returns screens key"
    (let [result (cli/dispatch ["screen"])]
      (is (= :screen (:subcommand result)))
      (is (contains? result :screens)))))

(deftest dispatch-screen-with-name-test
  (testing "screen with name arg returns results and count"
    (let [result (cli/dispatch ["screen" "my-screen"])]
      (is (= :screen (:subcommand result)))
      (is (contains? result :results))
      (is (contains? result :count)))))

(deftest dispatch-refresh-test
  (testing "refresh subcommand runs without error"
    (let [result (cli/dispatch ["refresh"])]
      (is (= :refresh (:subcommand result))))))

(deftest dispatch-order-disabled-test
  (testing "order subcommand returns orders_disabled by default"
    (let [result (cli/dispatch ["order" "BUY" "AAPL" "1"])]
      (is (= "orders_disabled" (:error result))))))

(deftest dispatch-unknown-subcommand-test
  (testing "unknown subcommand returns error"
    (let [result (cli/dispatch ["bogus-command"])]
      (is (contains? result :error)))))

(deftest dispatch-help-test
  (testing "--help flag returns :help true"
    (let [result (cli/dispatch ["--help"])]
      (is (:help result)))))

(deftest dispatch-no-args-test
  (testing "no arguments returns error"
    (let [result (cli/dispatch [])]
      (is (contains? result :error)))))

(deftest dispatch-account-option-test
  (testing "--account flag is forwarded to portfolio"
    (let [result (cli/dispatch ["--account" "U9999999" "portfolio"])]
      (is (= :portfolio (:subcommand result)))
      (is (= "U9999999" (:account-id result))))))

(deftest run-subcommand-multimethod-test
  (testing "run-subcommand dispatches by keyword"
    (let [r (cli/run-subcommand :portfolio {:account "DU123456"} [])]
      (is (= :portfolio (:subcommand r))))
    (let [r (cli/run-subcommand :screen {} [])]
      (is (= :screen (:subcommand r))))
    (let [r (cli/run-subcommand :screen {} ["my-screen"])]
      (is (= :screen (:subcommand r)))
      (is (contains? r :results)))
    (let [r (cli/run-subcommand :unknown-cmd {} [])]
      (is (contains? r :error)))))
