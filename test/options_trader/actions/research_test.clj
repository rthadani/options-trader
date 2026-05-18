(ns options-trader.actions.research-test
  "Tests the dispatch shape of read-only research tools. Real source backends
   are not under test here — when sources aren't configured the data layer
   returns :unavailable, and we verify the handler wraps that into a
   well-formed action response."
  (:require [clojure.test :refer [deftest is testing]]
            [options-trader.actions.core :as actions]
            [options-trader.actions.research]
            [options-trader.mcp.tools :as tools]))

(deftest fetch-news-returns-structured-result
  (let [r (actions/handle-action {:type :research/fetch-news :symbol "AAPL"})]
    (is (:ok r))
    (is (= "AAPL" (-> r :result :symbol)))
    (is (contains? (:result r) :headlines))
    (is (contains? (:result r) :sentiment))))

(deftest fetch-news-respects-include-filter
  (let [r (actions/handle-action {:type :research/fetch-news
                                  :symbol "AAPL" :include [:sentiment]})]
    (is (contains? (:result r) :sentiment))
    (is (not (contains? (:result r) :headlines))
        "headlines omitted when not in :include")))

(deftest fetch-filings-returns-symbol-and-filings
  (let [r (actions/handle-action {:type :research/fetch-filings :symbol "AAPL"})]
    (is (:ok r))
    (is (= "AAPL" (-> r :result :symbol)))
    (is (contains? (:result r) :filings))))

(deftest fetch-xbrl-facts-passes-cik-through
  (let [r (actions/handle-action {:type :research/fetch-xbrl-facts
                                  :cik "0000320193"})]
    (is (:ok r))
    (is (= "0000320193" (-> r :result :cik)))
    (is (contains? (:result r) :facts))))

(deftest fetch-corporate-actions-all-slice
  (let [r (actions/handle-action {:type :research/fetch-corporate-actions
                                  :symbol "AAPL"})]
    (is (:ok r))
    (let [res (:result r)]
      (is (contains? res :dividends))
      (is (contains? res :splits))
      (is (contains? res :actions)))))

(deftest fetch-corporate-actions-include-only-dividends
  (let [r (actions/handle-action {:type :research/fetch-corporate-actions
                                  :symbol "AAPL" :include [:dividends]})]
    (is (contains? (:result r) :dividends))
    (is (not (contains? (:result r) :splits)))
    (is (not (contains? (:result r) :actions)))))

(deftest fetch-fundamentals-returns-immediately-on-unavailable
  (let [start (System/currentTimeMillis)
        r     (actions/handle-action {:type :research/fetch-fundamentals
                                      :symbol "AAPL"})
        elapsed (- (System/currentTimeMillis) start)]
    (is (:ok r))
    (is (< elapsed 1000)
        "should short-circuit on :unavailable, not wait for the async timeout")
    (is (= "AAPL" (-> r :result :symbol)))
    (is (contains? (:result r) :fundamentals))))

(deftest fetch-earnings-history-shape
  (let [r (actions/handle-action {:type :research/fetch-earnings-history
                                  :symbol "AAPL"})]
    (is (:ok r))
    (is (= "AAPL" (-> r :result :symbol)))
    (is (contains? (:result r) :history))))

(deftest fetch-short-interest-default-includes-both
  (let [r (actions/handle-action {:type :research/fetch-short-interest
                                  :symbol "AAPL"})]
    (is (contains? (:result r) :history))
    (is (contains? (:result r) :borrow))))

(deftest mcp-call-tool-routes-to-research-handlers
  (testing "MCP tool names → research action types"
    (doseq [[tool args] [["fetch_news"              {:symbol "AAPL"}]
                         ["fetch_filings"           {:symbol "AAPL"}]
                         ["fetch_xbrl_facts"        {:cik "0000320193"}]
                         ["fetch_corporate_actions" {:symbol "AAPL"}]
                         ["fetch_fundamentals"      {:symbol "AAPL"}]
                         ["fetch_earnings_history"  {:symbol "AAPL"}]
                         ["fetch_short_interest"    {:symbol "AAPL"}]]]
      (let [r (tools/call-tool tool args {})]
        (is (:ok r) (str tool " returned an :ok response"))
        (is (map? (:result r)) (str tool " returned a result map"))))))

(deftest all-7-research-tools-registered-in-mcp
  (let [reg (tools/load-registry)]
    (doseq [nm ["fetch_news" "fetch_filings" "fetch_xbrl_facts"
                "fetch_corporate_actions" "fetch_fundamentals"
                "fetch_earnings_history" "fetch_short_interest"]]
      (is (contains? reg nm) (str nm " missing from MCP registry")))))
