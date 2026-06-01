(ns options-trader.tui.slash
  "Slash-command helpers. The live dispatch lives in `tui.main` —
   this namespace only holds shared utilities (currently the ticker
   normaliser used by /investigate)."
  (:require [clojure.string :as str]))

(defn normalise-symbol
  "Tickers are upper-case in the rest of the system; normalise here so
   /investigate aapl and /investigate AAPL hit the same scope."
  [s]
  (when s (str/upper-case (str/trim s))))
