(ns options-trader.data.universes-test
  "Fixture-driven tests for the universes namespace.
   All assertions run against test/resources/fixtures/sp500.html — no live HTTP."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [options-trader.data.universes :as u]))

;;; ── Fixture loading (pure, no network) ─────────────────────────────────────

(def ^:private fixture-html
  "Lazily loaded S&P 500 Wikipedia fixture (HTML string)."
  (delay (slurp (io/resource "fixtures/sp500.html"))))

;;; ── extract-tickers tests ───────────────────────────────────────────────────

(deftest extract-tickers-basic-test
  (testing "extracts raw tickers from wikitable fixture"
    (let [tickers (u/extract-tickers @fixture-html 0)]
      (is (vector? tickers)
          "extract-tickers should return a vector")
      (is (>= (count tickers) 480)
          (str "should extract ≥480 tickers, got " (count tickers)))
      (is (some #{"BRK.B"} tickers)
          "raw tickers must include 'BRK.B' before normalisation")
      (is (some #{"AAPL"} tickers)
          "raw tickers must include 'AAPL'")))

  (testing "no blank entries in extracted tickers"
    (let [tickers (u/extract-tickers @fixture-html 0)]
      (is (not-any? clojure.string/blank? tickers)
          "extract-tickers must filter out blank values"))))

(deftest extract-tickers-reader-test
  (testing "accepts a java.io.Reader as src"
    (let [reader  (io/reader (io/resource "fixtures/sp500.html"))
          tickers (u/extract-tickers reader 0)]
      (is (>= (count tickers) 480)
          "reader path should also yield ≥480 tickers")
      (is (some #{"BRK.B"} tickers)
          "reader path must include BRK.B"))))

;;; ── normalise-symbol unit table ─────────────────────────────────────────────

(deftest normalise-symbol-unit-test
  (testing "dot replacements"
    (is (= "BRK B" (u/normalise-symbol "BRK.B"))
        "BRK.B must map to 'BRK B'")
    (is (= "BRK A" (u/normalise-symbol "BRK.A"))
        "BRK.A must map to 'BRK A'")
    (is (= "BF B"  (u/normalise-symbol "BF.B"))
        "BF.B must map to 'BF B'"))

  (testing "plain symbols pass through unchanged"
    (is (= "AAPL"  (u/normalise-symbol "AAPL")))
    (is (= "MSFT"  (u/normalise-symbol "MSFT")))
    (is (= "GOOGL" (u/normalise-symbol "GOOGL"))))

  (testing "footnote markers are stripped before dot replacement"
    (is (= "BRK B" (u/normalise-symbol "BRK.B[d]"))
        "bracket footnote stripped then dot replaced")
    (is (= "BRK B" (u/normalise-symbol "BRK.B^1"))
        "caret footnote stripped then dot replaced")
    (is (= "AAPL"  (u/normalise-symbol "AAPL[a]"))
        "bracket footnote stripped from plain symbol")
    (is (= "AAPL"  (u/normalise-symbol "AAPL^2"))
        "caret footnote stripped from plain symbol"))

  (testing "whitespace is trimmed"
    (is (= "AAPL" (u/normalise-symbol " AAPL "))
        "leading/trailing whitespace must be trimmed")))

;;; ── Full pipeline integration (no HTTP) ─────────────────────────────────────

(deftest normalise-symbols-pipeline-test
  (testing "extract + normalise pipeline on S&P 500 fixture"
    (let [raw-tickers (u/extract-tickers @fixture-html 0)
          normalised  (u/normalise-symbols raw-tickers)]
      (is (>= (count normalised) 480)
          (str "normalised list should have ≥480 entries, got " (count normalised)))
      (is (some #{"BRK B"} normalised)
          "normalised list must contain 'BRK B' (space, not dot)")
      (is (not (some #{"BRK.B"} normalised))
          "normalised list must NOT contain 'BRK.B' (the raw dot form)")
      (is (some #{"AAPL"} normalised)
          "common ticker AAPL must survive normalisation"))))

;;; ── seed-builtins! guard ────────────────────────────────────────────────────

(deftest seed-builtins-placeholder-test
  (testing "seed-builtins! throws until Phase 3"
    (is (thrown? UnsupportedOperationException (u/seed-builtins!))
        "seed-builtins! must throw UnsupportedOperationException in Phase 2")))
