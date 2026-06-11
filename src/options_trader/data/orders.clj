(ns options-trader.data.orders
  "Pluggable order-execution source. Mirrors data.options / data.news /
   data.edgar — `make-source` dispatches on :type to a concrete adapter.
   Swap brokers by registering a new defmethod; the action handler in
   actions.orders stays unchanged.

   `place-order!` receives a neutral spec map with the validated action
   fields (:symbol :sec-type :side :quantity :order-type :limit-price
   :tif :outside-rth? :combo-legs); the adapter is responsible for
   projecting to its broker's wire format. Returns the broker-assigned
   order-id, or :unavailable.

   `cancel-order!` takes the same order-id and returns it on success or
   :unavailable on failure."
  (:require [clojure.string :as str]
            [options-trader.data.ibkr :as ibkr]
            [taoensso.timbre :as log]))

(defprotocol IOrderSource
  (place-order!  [this spec callback])
  (cancel-order! [this order-id]))

(deftype UnavailableOrderSource []
  IOrderSource
  (place-order!  [_ _ _] :unavailable)
  (cancel-order! [_ _]   :unavailable))

(def ^:private default-source (UnavailableOrderSource.))


(defn- ibkr-contract
  "Project the neutral spec into an IB contract map. BAG carries combo-legs;
   STK / OPT use the standard ibkr/->contract defaults."
  [{:keys [symbol sec-type exchange currency combo-legs conid]}]
  (let [sec (or (some-> sec-type str str/upper-case) "STK")]
    (if (= "BAG" sec)
      {:symbol     symbol
       :sec-type   "BAG"
       :exchange   (or exchange "SMART")
       :currency   (or currency "USD")
       :combo-legs (vec combo-legs)}
      (cond-> (ibkr/->contract symbol)
        sec-type (assoc :sec-type sec)
        exchange (assoc :exchange exchange)
        currency (assoc :currency currency)
        conid    (assoc :conid conid)))))

(defn- ibkr-order
  "Project the neutral spec into an IB order map. MKT and LMT only for v1."
  [{:keys [side quantity order-type limit-price tif outside-rth? transmit?]}]
  (let [type* (str/upper-case (str order-type))]
    (cond-> {:action         (str/upper-case (str side))
             :total-quantity (double quantity)
             :order-type     type*
             :tif            (or tif "DAY")
             :outside-rth    (boolean outside-rth?)
             :transmit       (if (nil? transmit?) true (boolean transmit?))}
      (= "LMT" type*)
      (assoc :lmt-price (double limit-price)))))

(deftype IbkrOrderSource [ib-client]
  IOrderSource
  (place-order! [_ spec callback]
    (if-not ib-client
      :unavailable
      (let [contract (ibkr-contract spec)
            order    (ibkr-order spec)]
        (ibkr/req-place-order! ib-client contract order callback))))
  (cancel-order! [_ order-id]
    (if-not ib-client
      :unavailable
      (ibkr/req-cancel-order! ib-client (long order-id)))))


(deftype MockOrderSource [calls counter]
  IOrderSource
  (place-order! [_ spec _callback]
    (swap! calls conj {:type :place :spec spec})
    (swap! counter inc))
  (cancel-order! [_ order-id]
    (swap! calls conj {:type :cancel :order-id order-id})
    order-id))

(defn mock-source
  "Test helper. Returns a MockOrderSource that records every call into an
   atom and hands out monotonically-increasing synthetic order-ids."
  ([] (mock-source 1000))
  ([start-id]
   (->MockOrderSource (atom []) (atom (dec start-id)))))

(defn mock-calls
  "Read the recorded calls from a MockOrderSource."
  [^MockOrderSource src]
  @(.-calls src))


(defmulti make-source
  "Build an order source from a config map. Dispatches on :type."
  :type)

(defmethod make-source :default [_cfg]
  default-source)

(defmethod make-source :ibkr [{:keys [ib-client]}]
  (->IbkrOrderSource ib-client))

(defmethod make-source :mock [_cfg]
  (mock-source))
