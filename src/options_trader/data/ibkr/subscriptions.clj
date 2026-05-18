(ns options-trader.data.ibkr.subscriptions
  "Atom-backed subscription manager with a configurable cap (default 50)
   on concurrent market-data streams.")

(def default-cap 50)

(defn create-manager
  "Return a manager handle: a map with :subs (atom) and :cap (int)."
  ([]    (create-manager default-cap))
  ([cap] {:subs (atom {}) :cap cap}))

(defn subscribe!
  "Add a subscription for conid with the given kind.
   Returns {:ok true :conid c :kind k} or {:error :subscription-cap-exceeded}."
  [{:keys [subs cap]} conid kind]
  (if (>= (count @subs) cap)
    {:error :subscription-cap-exceeded}
    (do (swap! subs assoc conid {:kind kind :subscribed-at (System/currentTimeMillis)})
        {:ok true :conid conid :kind kind})))

(defn unsubscribe!
  "Remove the subscription for conid.
   Returns {:ok true :conid c} or {:error :not-subscribed :conid c}."
  [{:keys [subs]} conid]
  (if (contains? @subs conid)
    (do (swap! subs dissoc conid)
        {:ok true :conid conid})
    {:error :not-subscribed :conid conid}))

(defn active-subs
  "Return a map of {conid {:kind k :subscribed-at ms}} for all active subs."
  [{:keys [subs]}]
  @subs)
