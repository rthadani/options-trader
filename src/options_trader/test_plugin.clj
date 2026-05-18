(ns options-trader.test-plugin
  (:require [kaocha.plugin :as plugin]
            [kaocha.result :as result]))

(plugin/defplugin :options-trader.test-plugin/ran-summary
  (post-run [r]
    (let [suites (get r :kaocha.result/tests [])
          totals (result/totals suites)
          n      (:kaocha.result/count totals 0)]
      (println (str "Ran " n " tests")))
    r))
