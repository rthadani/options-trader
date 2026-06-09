(ns options-trader.data.ibkr.request-id
  "Atom-backed monotonic request-id allocator and callback registry.
   Provides a multimethod skeleton for routing IBKR response messages."
  (:refer-clojure :exclude [resolve]))


(defonce ^:private counter  (atom 0))
(defonce ^:private registry (atom {}))

(defn reset-state!
  "Reset allocator and registry to empty. Use in tests only."
  []
  (reset! counter 0)
  (reset! registry {}))


(defn next-id!
  "Return the next monotonically increasing request id (positive integer)."
  []
  (swap! counter inc))


(defn register!
  "Associate id with callback cb. Returns id."
  [id cb]
  (swap! registry assoc id cb)
  id)

(defn resolve
  "Return the callback registered for id, or nil when not found."
  [id]
  (get @registry id))

(defn complete!
  "Remove the callback for id from the registry. Returns nil.
   Safe to call for unknown ids."
  [id]
  (swap! registry dissoc id)
  nil)

(defn pending-ids
  "Return the set of all currently-registered request ids."
  []
  (set (keys @registry)))


(defmulti response-handler
  "Dispatch an IBKR response message map by its :type key.
   Handlers receive the full message map and return a result map."
  :type)

(defmethod response-handler :error [msg]
  {:handled :error :req-id (:req-id msg) :code (:code msg)})

(defmethod response-handler :next-valid-id [msg]
  {:handled :next-valid-id :id (:id msg)})

(defmethod response-handler :default [msg]
  {:handled :unknown :type (:type msg)})
