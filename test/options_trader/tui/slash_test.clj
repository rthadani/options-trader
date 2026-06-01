(ns options-trader.tui.slash-test
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.tui.slash :as sl]))

(deftest normalise-symbol-uppercases-and-trims
  (is (= "AAPL" (sl/normalise-symbol "aapl")))
  (is (= "AAPL" (sl/normalise-symbol "  aapl  ")))
  (is (= "BRK.B" (sl/normalise-symbol "brk.b"))))

(deftest normalise-symbol-nil-safe
  (is (nil? (sl/normalise-symbol nil))))
