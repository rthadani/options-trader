(ns options-trader.actions.orders
  "Order placement + cancellation. Confirm-gated: first call returns a
   preview; a second call with :confirm? true actually sends. Both
   surfaces (MCP, CLI, TUI) dispatch through here so the guard chain
   can't be skipped.

   The actual broker call goes through data.orders/IOrderSource — pass
   the source as :order-source on the action map, or set :ib-client and
   the handler will fall back to an :ibkr-backed source for backwards
   compatibility."
  (:require [clojure.string :as str]
            [options-trader.actions.core :as actions]
            [options-trader.data.orders :as orders]
            [taoensso.timbre :as log]))

(def ^:private valid-sides       #{"BUY" "SELL"})
(def ^:private valid-order-types #{"MKT" "LMT"})

(defn- coerce-side [v] (some-> v str str/upper-case str/trim))
(defn- coerce-type [v] (some-> v str str/upper-case str/trim))

(defn- validate-place
  "Return a vector of error keywords for missing/invalid fields. Empty vec
   means valid. Multi-leg legs are required for sec-type BAG."
  [{:keys [side quantity order-type limit-price sec-type combo-legs symbol]}]
  (let [sec   (or (some-> sec-type str str/upper-case) "STK")
        side* (coerce-side side)
        type* (coerce-type order-type)]
    (cond-> []
      (not (valid-sides side*))
      (conj :invalid-side)

      (not (and (number? quantity) (pos? quantity)))
      (conj :invalid-quantity)

      (not (valid-order-types type*))
      (conj :invalid-order-type)

      (and (= "LMT" type*) (not (number? limit-price)))
      (conj :limit-price-required)

      (and (= "BAG" sec) (not (and (sequential? combo-legs) (seq combo-legs))))
      (conj :combo-legs-required)

      (and (not= "BAG" sec) (str/blank? (str symbol)))
      (conj :symbol-required))))

(defn- notional
  "Best-effort notional in account currency. LMT only — for MKT and BAG we
   often don't have a price at preview time."
  [{:keys [order-type limit-price quantity sec-type combo-legs]}]
  (let [sec  (or (some-> sec-type str str/upper-case) "STK")
        mult (cond
               (= "OPT" sec) 100.0
               (= "BAG" sec) 100.0
               :else         1.0)]
    (when (and (number? limit-price) (number? quantity)
               (= "LMT" (coerce-type order-type)))
      (* (double quantity) (double limit-price) mult
         (if (= "BAG" sec)
           (->> combo-legs (map (comp #(or % 1) :ratio)) (reduce +))
           1.0)))))

(defn- neutral-spec
  "Pull the broker-neutral fields out of the action map. Whatever lands here
   is what the IOrderSource adapter sees."
  [action]
  (select-keys action
    [:symbol :sec-type :exchange :currency :conid :combo-legs
     :side :quantity :order-type :limit-price :tif :outside-rth? :transmit?]))

(defn- preview [action]
  (let [spec (neutral-spec action)]
    {:preview   {:spec     spec
                 :notional (notional spec)}
     :message   (str "Will submit " (coerce-side (:side action)) " "
                     (:quantity action) " "
                     (or (:symbol action) "<BAG>") " @ "
                     (coerce-type (:order-type action))
                     (when-let [p (:limit-price action)] (str " " p))
                     ". Re-call with :confirm? true to send.")
     :confirm?  false}))

(defn- resolve-source
  "If the action carries an :order-source use it. Else fall back to an IBKR
   source wrapping :ib-client. Else the unavailable default."
  [{:keys [order-source ib-client]}]
  (or order-source
      (when ib-client (orders/make-source {:type :ibkr :ib-client ib-client}))
      (orders/make-source {})))

(defmethod actions/handle-action :place-order
  [{:keys [allow-orders? confirm?] :as action}]
  (let [errors (validate-place action)]
    (cond
      (seq errors)
      {:error :invalid-order :reasons errors :action action}

      (not confirm?)
      (preview action)

      (not allow-orders?)
      {:error   :orders-disabled
       :message "Order execution is gated. Set :allow-orders? true in ctx."}

      :else
      (let [src    (resolve-source action)
            spec   (neutral-spec action)
            result (orders/place-order! src spec nil)]
        (if (= :unavailable result)
          {:error   :send-failed
           :message "Order source rejected the order (no broker connection?). Check logs."}
          (do (log/infof "placed order id=%s %s %s %s"
                         result (coerce-side (:side action))
                         (:quantity action) (or (:symbol action) "<BAG>"))
              {:ok       true
               :order-id result
               :spec     spec}))))))

(defmethod actions/handle-action :cancel-order
  [{:keys [allow-orders? order-id confirm?] :as action}]
  (cond
    (nil? order-id)
    {:error :missing-order-id :message "order-id is required"}

    (not (number? order-id))
    {:error :invalid-order-id :message "order-id must be a number"}

    (not confirm?)
    {:preview  {:order-id order-id}
     :message  (str "Will cancel order " order-id
                    ". Re-call with :confirm? true to send.")
     :confirm? false}

    (not allow-orders?)
    {:error   :orders-disabled
     :message "Order cancellation is gated. Set :allow-orders? true in ctx."}

    :else
    (let [src    (resolve-source action)
          result (orders/cancel-order! src order-id)]
      (if (= :unavailable result)
        {:error   :send-failed
         :message "Order source rejected the cancel (no broker connection?)."}
        (do (log/infof "cancel-order sent for id=%s" order-id)
            {:ok true :order-id order-id})))))
