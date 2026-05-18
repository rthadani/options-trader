(ns options-trader.actions.core
  "Action handler dispatch for the options-trader system.
   An action is a user- or system-initiated intent (e.g. :place-order,
   :cancel-order, :run-screen) routed through handle-action.")

(defmulti handle-action
  "Route an action map to the appropriate handler fn by its :type key.
   Register concrete handlers via defmethod."
  :type)

(defmethod handle-action :default [action]
  {:error :unknown-action-type :type (:type action)})

(defmulti validate-action
  "Validate an action map before execution.
   Returns {:valid true} or {:valid false :errors [...]}."
  :type)

(defmethod validate-action :default [_action]
  {:valid true})

(def ^:const action-types
  #{:place-order :cancel-order :run-screen
    :export-portfolio :refresh-quotes :connect-tws})
