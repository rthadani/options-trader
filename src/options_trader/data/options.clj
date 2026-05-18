(ns options-trader.data.options
  (:require [options-trader.data.ibkr :as ibkr]))

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

(defmulti make-source
  "Construct an options source from a config map. Dispatches on :type."
  :type)

(defmethod make-source :default [_cfg]
  default-source)

(defmethod make-source :ibkr [{:keys [ib-client]}]
  (IbkrOptionsSource. ib-client))

(defmethod make-source :mock [_cfg]
  (MockOptionsSource.))
