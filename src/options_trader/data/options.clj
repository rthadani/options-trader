(ns options-trader.data.options
  (:require [clj-yfinance.experimental.options :as yfo]
            [options-trader.data.ibkr :as ibkr]
            [taoensso.timbre :as log])
  (:import [java.time Instant ZoneOffset]))

(defprotocol IOptionsSource
  "Protocol for options data sources."
  (req-chain [this underlying expiry callback]
    "Request the option chain for underlying at expiry. Invokes callback with the chain map.
     Returns the allocated req-id or :unavailable.")
  (req-contract-details [this contract callback]
    "Request contract details for a contract spec map. Invokes callback with the details map.
     Returns the allocated req-id or :unavailable."))

(deftype UnavailableOptionsSource []
  IOptionsSource
  (req-chain            [_ _underlying _expiry _callback] :unavailable)
  (req-contract-details [_ _contract _callback]           :unavailable))

(def ^:private default-source (UnavailableOptionsSource.))

(defn- resolve-conid!
  "Block on contract-details for `underlying` and return its conid as an int.
   Pass-through when :conid is already set. Returns nil if lookup fails."
  [ib-client underlying]
  (if-let [c (:conid underlying)]
    (int c)
    (let [p (promise)]
      (ibkr/req-contract-details ib-client
        (ibkr/->contract underlying)
        (fn [events] (deliver p events)))
      (let [evs (deref p 10000 nil)
            cd  (first evs)]
        (some-> (or (:conid cd) (get-in cd [:contract :conid])) int)))))

(deftype IbkrOptionsSource [ib-client]
  IOptionsSource
  (req-chain [_ underlying expiry callback]
    (let [conid (resolve-conid! ib-client underlying)]
      (if-not conid
        (do (callback [{:type :error
                        :message (str "could not resolve conid for "
                                      (:symbol underlying))}])
            :unavailable)
        (ibkr/req-option-chain ib-client
                                (assoc underlying :conid conid)
                                expiry callback))))
  (req-contract-details [_ contract callback]
    (ibkr/req-contract-details ib-client contract callback)))

(deftype MockOptionsSource []
  IOptionsSource
  (req-chain [_ underlying expiry callback]
    (callback {:type :mock-chain :underlying underlying :expiry expiry})
    1)
  (req-contract-details [_ contract callback]
    (callback {:type :mock-contract-details :contract contract})
    2))

(defn- epoch->date-str [secs]
  (when (number? secs)
    (-> (Instant/ofEpochSecond (long secs))
        (.atZone ZoneOffset/UTC)
        .toLocalDate
        str)))

(defn- yahoo->event
  "Project clj-yfinance's chain map into the event shape collapse-chain
   consumes, plus a :contracts payload the action handler propagates so
   premiums + greeks come back from a single fetch_option_chain call."
  [data]
  {:type        :security-definition-optional-parameter
   :exchange    "YAHOO"
   :expirations (->> (:expiration-dates data) (keep epoch->date-str) vec)
   :strikes     (vec (:strikes data))
   :contracts   {:calls (vec (:calls data))
                 :puts  (vec (:puts data))}
   :quote       (:quote data)})

(deftype YahooOptionsSource []
  IOptionsSource
  (req-chain [_ underlying _expiry callback]
    (let [sym (:symbol underlying)]
      (try
        (if-let [data (yfo/fetch-options sym)]
          (do (callback [(yahoo->event data)])
              sym)
          (do (callback [{:type :error
                          :message (str "yahoo returned no chain for " sym)}])
              :unavailable))
        (catch Throwable t
          (log/warnf t "yahoo option-chain failed for %s" sym)
          (callback [{:type :error :message (.getMessage t)}])
          :unavailable))))
  (req-contract-details [_ _contract callback]
    (callback [{:type :error :message "yahoo source has no contract-details"}])
    :unavailable))

(defmulti make-source
  "Construct an options source from a config map. Dispatches on :type."
  :type)

(defmethod make-source :default [_cfg]
  default-source)

(defmethod make-source :ibkr [{:keys [ib-client]}]
  (IbkrOptionsSource. ib-client))

(defmethod make-source :yahoo [_cfg]
  (YahooOptionsSource.))

(defmethod make-source :mock [_cfg]
  (MockOptionsSource.))
