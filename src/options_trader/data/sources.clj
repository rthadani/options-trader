(ns options-trader.data.sources
  "Process-wide registry of installed default data sources, keyed by
   domain (:news, :edgar, :fundamentals, :earnings, :short-interest).
   `lookup` returns nil when nothing is installed so callers can fall
   back to their own Unavailable stub.")

(defonce ^:private registry (atom {}))

(defn default-source [domain]
  (get @registry domain))

(defn install! [domain src]
  (swap! registry assoc domain src)
  src)

(defn install-all!
  "Install many domain → source bindings at once. Nil values are skipped
   so callers can pass `(when ds (build-cache-src))` without guarding."
  [m]
  (swap! registry merge (into {} (remove (comp nil? val)) m))
  nil)

(defn clear! []
  (reset! registry {}))

(defn snapshot []
  @registry)
