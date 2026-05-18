(ns options-trader.tui.runtime-context-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [options-trader.tui.runtime-context :as rc]))

(deftest build-base-and-dynamic-markers-present
  (let [result (rc/build {:dynamic-block "spawn info here"})]
    (is (str/includes? result rc/base-marker))
    (is (str/includes? result rc/dynamic-marker))))

(deftest build-all-three-markers-when-overlay-provided
  (let [result (rc/build {:overlay "user overlay content" :dynamic-block "dynamic block"})]
    (is (str/includes? result rc/base-marker))
    (is (str/includes? result rc/overlay-marker))
    (is (str/includes? result rc/dynamic-marker))))

(deftest build-no-overlay-marker-when-overlay-absent
  (let [result (rc/build {:dynamic-block "some dynamic"})]
    (is (not (str/includes? result rc/overlay-marker)))))

(deftest build-overlay-content-present-in-output
  (let [overlay "MY CUSTOM OVERLAY RULES"
        result  (rc/build {:overlay overlay :dynamic-block "x"})]
    (is (str/includes? result overlay))))

(deftest build-dynamic-content-present-in-output
  (let [dyn    "session=abc model=claude"
        result (rc/build {:dynamic-block dyn})]
    (is (str/includes? result dyn))))

(deftest build-base-content-includes-role-section
  (let [result (rc/build {:dynamic-block ""})]
    (is (str/includes? result "## Role"))))

(deftest build-base-content-includes-tools-section
  (let [result (rc/build {:dynamic-block ""})]
    (is (str/includes? result "## Tools"))))

(deftest build-base-content-includes-house-rules-section
  (let [result (rc/build {:dynamic-block ""})]
    (is (str/includes? result "## House Rules"))))

(deftest build-returns-string
  (is (string? (rc/build {:dynamic-block "test"}))))

(deftest build-ordering-base-before-overlay-before-dynamic
  (let [result (rc/build {:overlay "OVERLAY" :dynamic-block "DYNAMIC"})]
    (is (< (.indexOf result rc/base-marker)
           (.indexOf result rc/overlay-marker)
           (.indexOf result rc/dynamic-marker)))))
