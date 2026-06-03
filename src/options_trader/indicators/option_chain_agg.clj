(ns options-trader.indicators.option-chain-agg
  "Option-chain aggregation: 7 DOUBLE columns derived from option_chain
   scoped to ATM ±1 strike of the front two expirations. Source SQL:
   resources/sql/indicators.sql (option-chain-agg, option-chain-agg-for)."
  (:require [options-trader.db.queries.indicators :as q]))

(def ^:private oca-double-cols
  [:option_oi_total :option_vol_total :atm_straddle_price
   :implied_move_pct :put_call_oi_ratio :put_call_volume_ratio
   :bid_ask_spread_pct])

(defn refresh-option-chain-agg!
  "Compute option-chain aggregates and upsert into latest_indicators.
   With a symbols list, restricts to those symbols; otherwise processes
   every symbol in bars_daily."
  [ds & [symbols]]
  (q/ensure-double-columns! ds oca-double-cols)
  (let [sqlvec (if (seq symbols)
                 (q/option-chain-agg-for-sqlvec {:symbols (vec symbols)})
                 (q/option-chain-agg-sqlvec))]
    (q/run-static-recompute! ds sqlvec)))
