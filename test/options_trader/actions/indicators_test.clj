(ns options-trader.actions.indicators-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.actions.core :as actions]
            [options-trader.actions.indicators :as ind]
            [options-trader.test-util :as tu]))

(deftest propose-spec-parses-llm-json
  (let [res (ind/propose-spec
              "sortino_ratio"
              {:sh-fn (tu/canned-sh
                        "{\"kind\":\"RSI\",\"params\":[14],\"column\":\"sortino_ratio\",\"rationale\":\"sample\"}")})]
    (is (= :RSI (get-in res [:proposal :kind])))
    (is (= [14] (get-in res [:proposal :params])))
    (is (= :sortino_ratio (get-in res [:proposal :column])))))

(deftest propose-spec-unsupported-passes-through
  (let [res (ind/propose-spec
              "voodoo_index"
              {:sh-fn (tu/canned-sh
                        "{\"unsupported\":true,\"reason\":\"no ta4j class\"}")})]
    (is (true? (:unsupported res)))
    (is (string? (:reason res)))))

(deftest propose-indicator-action-rejects-blank-column
  (let [res (actions/handle-action {:type :propose-indicator :column ""})]
    (is (some? (:error res)))))

(deftest propose-indicator-action-rejects-existing-column
  (let [res (actions/handle-action {:type :propose-indicator :column "rsi_14"})]
    (is (re-find #"already present" (:error res)))))

(deftest add-indicator-without-confirm-returns-preview
  (let [res (actions/handle-action
              {:type :add-indicator
               :spec {:kind :RSI :params [9] :column :rsi_9}})]
    (is (= {:kind :RSI :params [9] :column :rsi_9} (:preview res)))
    (is (re-find #"Re-call with :confirm" (:message res)))))

(deftest add-indicator-rejects-unknown-kind
  (let [res (actions/handle-action
              {:type     :add-indicator
               :confirm? true
               :spec     {:kind :NotARealKind :params [] :column :bogus}})]
    (is (re-find #"kind not in dispatch" (:error res)))))

(deftest add-indicator-rejects-existing-column-on-confirm
  (let [res (actions/handle-action
              {:type     :add-indicator
               :confirm? true
               :spec     {:kind :RSI :params [14] :column :rsi_14}})]
    (is (re-find #"already present" (:error res)))))
