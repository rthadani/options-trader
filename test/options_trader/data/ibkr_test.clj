(ns options-trader.data.ibkr-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [options-trader.data.ibkr            :as ibkr]
            [options-trader.data.ibkr.pacer       :as pacer]
            [options-trader.data.ibkr.request-id  :as req-id]
            [options-trader.data.ibkr.subscriptions :as subs]))

(def ^:dynamic *calls* nil)

(use-fixtures :each
  (fn [f]
    (req-id/reset-state!)
    (pacer/reset-state!)
    (binding [*calls* (atom [])]
      (with-redefs [ibkr/send-request! (fn [_conn req] (swap! *calls* conj req) nil)]
        (f)))
    (req-id/reset-state!)
    (pacer/reset-state!)))

(deftest req-historical-bars-allocates-request-id
  (testing "returns a positive req-id and sends exactly one request"
    (let [id (ibkr/req-historical-bars :conn {:symbol "AAPL"} "1 min" "1 D" identity)]
      (is (pos? id))
      (is (= 1 (count @*calls*)))
      (is (= :req-historical-bars (:type (first @*calls*))))
      (is (= id (:req-id (first @*calls*)))))))

(deftest req-historical-bars-consumes-pacer-token
  (testing "pacer token count decreases after the call"
    (let [before (:tokens (pacer/stats))]
      (ibkr/req-historical-bars :conn {:symbol "AAPL"} "1 min" "1 D" identity)
      (is (< (:tokens (pacer/stats)) before)))))

(deftest req-historical-bars-registers-callback
  (testing "the callback is recorded in pending under the returned id"
    (let [id (ibkr/req-historical-bars :conn {:symbol "MSFT"} "5 mins" "5 D" identity)]
      (is (contains? (ibkr/pending-ids) id)))))

(deftest req-contract-details-sends-correct-type
  (testing "sends :req-contract-details with the contract attached"
    (let [contract {:symbol "TSLA" :sec-type :STK}
          id       (ibkr/req-contract-details :conn contract identity)]
      (is (pos? id))
      (is (= 1 (count @*calls*)))
      (is (= :req-contract-details (:type (first @*calls*))))
      (is (= contract (:contract (first @*calls*)))))))

(deftest req-market-data-registers-subscription
  (testing "registers the conid in the injected subscription manager"
    (let [mgr (subs/create-manager 10)
          id  (ibkr/req-market-data :conn {:conid 12345} "" identity mgr)]
      (is (pos? id))
      (is (= 1 (count @*calls*)))
      (is (contains? (subs/active-subs mgr) 12345)))))

(deftest req-market-data-enforces-subscription-cap
  (testing "returns an error map when the subscription cap is exceeded"
    (let [mgr (subs/create-manager 2)]
      (ibkr/req-market-data :conn {:conid 1} "" identity mgr)
      (ibkr/req-market-data :conn {:conid 2} "" identity mgr)
      (let [result (ibkr/req-market-data :conn {:conid 3} "" identity mgr)]
        (is (= :subscription-cap-exceeded (:error result)))
        (is (= 2 (count @*calls*)))))))

(deftest req-market-data-snapshot-sets-snapshot-flag
  (testing "snapshot variant sends :snapshot true"
    (ibkr/req-market-data-snapshot :conn {:conid 99} "" identity)
    (is (= 1 (count @*calls*)))
    (is (= :req-market-data (:type (first @*calls*))))
    (is (true? (:snapshot (first @*calls*))))))

(deftest req-account-summary-sends-tags
  (testing "sends :req-account-summary with the tags string"
    (let [tags "NetLiquidation,TotalCashValue"
          id   (ibkr/req-account-summary :conn tags identity)]
      (is (pos? id))
      (is (= 1 (count @*calls*)))
      (is (= :req-account-summary (:type (first @*calls*))))
      (is (= tags (:tags (first @*calls*)))))))

(deftest ->order-builds-canonical-map
  (testing "->order normalises a minimal order spec"
    (let [o (ibkr/->order {:action :buy :quantity 1})]
      (is (= :buy (:action o)))
      (is (= 1 (:quantity o)))
      (is (= :market (:type o)))
      (is (= :day (:time-in-force o)))
      (is (true? (:transmit? o)))))
  (testing "limit-price / stop-price are forwarded when present"
    (let [o (ibkr/->order {:action :sell :quantity 10
                            :type :limit :limit-price 220.0})]
      (is (= :limit (:type o)))
      (is (= 220.0 (:limit-price o))))))

(deftest managed-accounts-defaults-to-nil
  (testing "with no captured event, managed-accounts and default-account are nil"
    (reset! (deref #'ibkr/managed-accounts-atom) nil)
    (is (nil? (ibkr/managed-accounts)))
    (is (nil? (ibkr/default-account)))))

(deftest managed-accounts-captured-from-handshake-event
  (testing "the handle-event! listener captures :managed-accounts payloads"
    (#'ibkr/capture-managed-accounts!
       {:type :managed-accounts :accounts-list "DU111111,DU987654"})
    (is (= ["DU111111" "DU987654"] (ibkr/managed-accounts)))
    (is (= "DU111111" (ibkr/default-account)))))

(deftest managed-accounts-trims-and-drops-blanks
  (#'ibkr/capture-managed-accounts!
     {:type :managed-accounts :accounts-list " A1 ,, B2 , "})
  (is (= ["A1" "B2"] (ibkr/managed-accounts))))

(deftest request-ids-are-monotonically-increasing
  (testing "successive req-* calls allocate strictly increasing req-ids"
    (let [id1 (ibkr/req-positions       :conn identity)
          id2 (ibkr/req-news-providers  :conn identity)
          id3 (ibkr/req-contract-details :conn {:symbol "SPY"} identity)]
      (is (< id1 id2 id3)))))
