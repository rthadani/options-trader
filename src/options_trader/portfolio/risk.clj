(ns options-trader.portfolio.risk)

(defn max-loss-for
  "Return the theoretical maximum loss for a single position map.
   Keys used: :opt-right :qty :avg-cost :strike
   Returns a number (dollars) or :unlimited."
  [{:keys [opt-right qty avg-cost strike]}]
  (let [multiplier 100]
    (cond
      (or (nil? opt-right) (= "" opt-right))
      (if (pos? qty)
        (* avg-cost qty)
        :unlimited)

      (and (pos? qty) (#{"C" "P"} opt-right))
      (* avg-cost qty multiplier)

      (and (neg? qty) (= "C" opt-right))
      :unlimited

      (and (neg? qty) (= "P" opt-right))
      (* strike (- qty) multiplier)

      :else 0)))

(defn margin-required
  "Estimate margin required for a single position map.
   Short options: 20% of notional (strike * 100) per contract.
   Long options and stock: 0.
   Returns a non-negative number."
  [{:keys [opt-right qty strike]}]
  (if (and (neg? qty) (#{"C" "P"} opt-right))
    (* 0.20 strike 100 (- qty))
    0))

(defn position-pct-net-liq
  "Return the position's market value as a percentage of net liquidation value.
   Returns nil when net-liq is zero or nil."
  [{:keys [market-value]} net-liq]
  (when (and net-liq (not (zero? net-liq)))
    (* 100.0 (/ market-value net-liq))))
