(ns options-trader.data.market-data
  (:require [options-trader.data.ibkr :as ibkr]))

(defprotocol IMarketDataSource
  "Protocol for market data sources."
  (get-quote [this contract tick-types callback]
    "Request a one-shot quote snapshot for contract. Invokes callback with each tick.
     Returns the allocated req-id or :unavailable.")
  (subscribe-quotes [this contract tick-types callback]
    "Subscribe to a live quote stream for contract. Invokes callback with each tick.
     Returns the allocated req-id or {:error :subscription-cap-exceeded}.")
  (snapshot [this contract tick-types callback]
    "Request a market-data snapshot for contract. Invokes callback with each tick.
     Returns the allocated req-id or :unavailable."))

(deftype UnavailableMarketDataSource []
  IMarketDataSource
  (get-quote        [_ _contract _tick-types _callback] :unavailable)
  (subscribe-quotes [_ _contract _tick-types _callback] :unavailable)
  (snapshot         [_ _contract _tick-types _callback] :unavailable))

(def ^:private default-source (UnavailableMarketDataSource.))

(deftype IbkrMarketDataSource [ib-client]
  IMarketDataSource
  (get-quote [_ contract tick-types callback]
    (ibkr/req-market-data-snapshot ib-client contract tick-types callback))
  (subscribe-quotes [_ contract tick-types callback]
    (ibkr/req-market-data ib-client contract tick-types callback))
  (snapshot [_ contract tick-types callback]
    (ibkr/req-market-data-snapshot ib-client contract tick-types callback)))

(deftype MockMarketDataSource []
  IMarketDataSource
  (get-quote [_ contract tick-types callback]
    (callback {:type :mock-quote :contract contract :tick-types tick-types})
    1)
  (subscribe-quotes [_ contract tick-types callback]
    (callback {:type :mock-tick :contract contract :tick-types tick-types})
    2)
  (snapshot [_ contract tick-types callback]
    (callback {:type :mock-snapshot :contract contract :tick-types tick-types})
    3))

(defmulti make-source
  "Construct a market data source from a config map. Dispatches on :type."
  :type)

(defmethod make-source :default [_cfg]
  default-source)

(defmethod make-source :ibkr [{:keys [ib-client]}]
  (IbkrMarketDataSource. ib-client))

(defmethod make-source :mock [_cfg]
  (MockMarketDataSource.))
