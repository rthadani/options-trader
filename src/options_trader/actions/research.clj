(ns options-trader.actions.research
  "Read-only research actions for single-instrument deep-dive workflows.
   Each handler is dispatched via actions/handle-action on :type. Per the
   project CLAUDE.md invariant, all tool calls funnel through actions/* so
   any future guard (rate-limit, audit, etc.) applies uniformly.

   Sources may be supplied in the action map under :source — otherwise the
   data ns's default-source (UnavailableSource stub) is used, so the call
   returns :unavailable instead of blowing up."
  (:require [clojure.string                   :as str]
            [options-trader.actions.core      :as actions]
            [options-trader.data.news         :as news]
            [options-trader.data.edgar        :as edgar]
            [options-trader.data.events       :as events]
            [options-trader.data.fundamentals :as fundamentals]
            [options-trader.data.earnings     :as earnings]
            [options-trader.data.short-interest :as si]
            [options-trader.data.options      :as options]
            [options-trader.data.market-data  :as md]
            [options-trader.data.quotes       :as quotes]
            [options-trader.data.ibkr         :as ibkr]))

(def ^:private default-timeout-ms 15000)

(defn- await-stream
  "Single-callback async fetcher → batched seq. Drains callback invocations
   into an atom until the terminal :end-type marker arrives, or timeout.
   Returns {:items [...]} or {:error :unavailable | :timeout, :received n}."
  [request-fn end-type timeout-ms]
  (let [items  (atom [])
        done   (promise)
        cb     (fn [item]
                 (if (= end-type (:type item))
                   (deliver done @items)
                   (swap! items conj item)))
        req-id (request-fn cb)]
    (cond
      (= :unavailable req-id) {:error :unavailable}
      :else
      (let [r (deref done timeout-ms ::timeout)]
        (if (= ::timeout r)
          {:error :timeout :received (count @items)}
          {:items r})))))

(defn- await-once
  "Single-shot async fetcher → delivered value or {:error ...}."
  [request-fn timeout-ms]
  (let [p      (promise)
        req-id (request-fn #(deliver p %))]
    (cond
      (= :unavailable req-id) {:error :unavailable}
      :else
      (let [r (deref p timeout-ms ::timeout)]
        (if (= ::timeout r) {:error :timeout} {:result r})))))

(defn- news-src    [action] (or (:source action) (news/make-source {})))
(defn- edgar-src   [action] (or (:source action) (edgar/default-source)))
(defn- events-src  [action] (or (:source action) (events/make-source {})))
(defn- fund-src    [action] (or (:source action) (fundamentals/make-source {})))
(defn- earn-src    [action] (or (:source action) (earnings/make-source {})))
(defn- si-src      [action] (or (:source action) (si/make-source {})))
(defn- opts-src    [action] (or (:source action) (options/make-source {})))

(defmethod actions/handle-action :research/fetch-news
  [{:keys [symbol include params timeout-ms] :as action
    :or   {include #{:headlines :sentiment} timeout-ms default-timeout-ms}}]
  (let [src (news-src action)
        inc (set include)
        out {:symbol symbol}]
    {:ok true
     :result
     (cond-> out
       (:headlines inc)
       (assoc :headlines
              (await-stream
                (fn [cb] (news/fetch-news-headlines symbol (or params {}) src cb))
                :historical-news-end
                timeout-ms))

       (:sentiment inc)
       (assoc :sentiment (news/fetch-news-sentiment symbol (or params {}) src)))}))

(defmethod actions/handle-action :research/fetch-filings
  [{:keys [symbol params] :as action}]
  {:ok     true
   :result {:symbol  symbol
            :filings (edgar/fetch-edgar-filings symbol (or params {}) (edgar-src action))}})

(defmethod actions/handle-action :research/fetch-xbrl-facts
  [{:keys [cik taxonomy] :as action :or {taxonomy :us-gaap}}]
  {:ok     true
   :result {:cik   cik
            :facts (edgar/fetch-edgar-facts cik taxonomy (edgar-src action))}})

(defmethod actions/handle-action :research/fetch-filing-body
  [{:keys [accession format] :as action :or {format :text}}]
  {:ok     true
   :result {:accession accession
            :format    format
            :body      (edgar/fetch-edgar-filing-body
                         accession (keyword format) (edgar-src action))}})

(defmethod actions/handle-action :research/fetch-filing-item
  [{:keys [accession item-id] :as action}]
  {:ok     true
   :result {:accession accession
            :item-id   item-id
            :item      (edgar/fetch-edgar-filing-item
                         accession item-id (edgar-src action))}})

(defmethod actions/handle-action :research/fetch-corporate-actions
  [{:keys [symbol include params] :as action
    :or   {include #{:all}}}]
  ;; events/fetch-split-history and fetch-actions are 2-arity only — no
  ;; source injection in their public API. They fall through to the
  ;; configured default-source. fetch-dividend-calendar does accept a source.
  (let [src (events-src action)
        p   (or params {})
        inc (set include)]
    {:ok true
     :result
     (cond-> {:symbol symbol}
       (or (:all inc) (:dividends inc))
       (assoc :dividends (events/fetch-dividend-calendar symbol p src))

       (or (:all inc) (:splits inc))
       (assoc :splits (events/fetch-split-history symbol p))

       (:all inc)
       (assoc :actions (events/fetch-actions symbol p)))}))

(defmethod actions/handle-action :research/fetch-fundamentals
  [{:keys [symbol params timeout-ms] :as action
    :or   {timeout-ms default-timeout-ms}}]
  (let [src (fund-src action)
        p   (or params {})
        raw (await-once
              (fn [cb] (fundamentals/fetch-fundamentals symbol p src cb))
              timeout-ms)]
    {:ok true
     :result {:symbol symbol
              :fundamentals
              (cond
                (:error raw) raw
                :else (fundamentals/normalise-fundamentals (:result raw) src))}}))

(defmethod actions/handle-action :research/fetch-earnings-history
  [{:keys [symbol params] :as action}]
  {:ok     true
   :result {:symbol  symbol
            :history (earnings/fetch-earnings-history symbol (or params {}) (earn-src action))}})

(defmethod actions/handle-action :research/fetch-short-interest
  [{:keys [symbol include params] :as action
    :or   {include #{:history :borrow}}}]
  (let [src (si-src action)
        p   (or params {})
        inc (set include)]
    {:ok true
     :result
     (cond-> {:symbol symbol}
       (:history inc) (assoc :history (si/fetch-si symbol p src))
       (:borrow inc)  (assoc :borrow  (si/fetch-borrow symbol src)))}))

;;; ── Options ─────────────────────────────────────────────────────────────────

(defn- collapse-chain
  "Roll up per-exchange `:security-definition-optional-parameter` events into
   a flat shape with union'd expirations and strikes. Optional `expiry-prefix`
   (e.g. \"202609\") restricts the returned expirations."
  [events expiry-prefix]
  (let [exps    (reduce (fn [s e] (into s (:expirations e))) #{} events)
        strikes (reduce (fn [s e] (into s (:strikes      e))) #{} events)
        exch    (mapv :exchange events)
        keep?   (if (str/blank? expiry-prefix)
                  (constantly true)
                  #(str/starts-with? (str %) expiry-prefix))]
    {:exchanges   (vec (distinct exch))
     :expirations (vec (sort (filter keep? exps)))
     :strikes     (vec (sort strikes))}))

(defmethod actions/handle-action :research/fetch-option-chain
  [{:keys [symbol expiry-prefix timeout-ms] :as action
    :or   {timeout-ms default-timeout-ms}}]
  (let [src    (opts-src action)
        events (await-once
                 (fn [cb]
                   (options/req-chain src (ibkr/->contract symbol) "" cb))
                 timeout-ms)]
    {:ok true
     :result (cond
               (:error events) (assoc events :symbol symbol)
               :else (-> (collapse-chain (:result events) expiry-prefix)
                         (assoc :symbol symbol)))}))

(defmethod actions/handle-action :research/fetch-quote
  [{:keys [ib-client source ds symbol avg-window]
    :or   {avg-window 14}}]
  (cond
    (str/blank? (str symbol))
    {:ok false :error :missing-symbol :message "symbol is required"}

    (nil? ds)
    {:ok false :error :unavailable
     :message "fetch_quote requires a DuckDB datasource in ctx"}

    :else
    (let [src (or source
                  (when ib-client (md/make-source {:type :ibkr :ib-client ib-client}))
                  (md/make-source {:type :unavailable}))
          q   (try (quotes/detailed-quote src ds symbol :avg-window avg-window)
                   (catch Throwable t {:error :exception :message (.getMessage t)}))]
      (case q
        :unavailable {:ok false :error :unavailable
                      :message "market-data source rejected the snapshot request"}
        :timeout     {:ok false :error :timeout
                      :message "no snapshot events within the source's window"}
        (if (and (map? q) (:error q))
          {:ok false :error (:error q) :message (:message q)}
          {:ok true :result q})))))

(defn- ->option-right
  "Normalise an option side to the keyword form ib-re-actor's translation
   table expects. Strings like \"P\"/\"Put\" silently translate to nil →
   IB Contract.right ends up null → TWS rejects the request."
  [r]
  (case (some-> r str str/lower-case)
    ("c" "call" ":call") :call
    ("p" "put"  ":put")  :put
    r))

(defmethod actions/handle-action :research/fetch-option-quote
  [{:keys [ib-client source symbol strike expiry right exchange currency]
    :or   {exchange "SMART" currency "USD"}}]
  (let [src  (or source
                  (when ib-client (md/make-source {:type :ibkr :ib-client ib-client}))
                  (md/make-source {:type :unavailable}))
        opts {:symbol symbol :expiry expiry :strike strike :right right
              :exchange exchange :currency currency}
        ;; Snapshot mode: TWS sends cached state + tick-snapshot-end. Reliable
        ;; for prev-session close + bid/ask after-hours; greeks rarely arrive.
        q0   (quotes/option-quote-snapshot src opts)
        ;; Hand TWS the close prices and let it back out IV + greeks — works
        ;; any time of day, no live market data needed.
        q    (if (and (map? q0) (nil? (:iv q0)) (nil? (:delta q0)))
               (let [calc (quotes/calc-option-greeks src opts)]
                 (if (map? calc) (merge q0 calc) q0))
               q0)]
    (cond
      (= :unavailable q)
      {:ok false :error :unavailable
       :message "market-data source rejected the OPT request"}

      (= :timeout q)
      {:ok false :error :timeout
       :message "no snapshot end within the source's window"}

      :else
      {:ok     true
       :result (merge {:symbol symbol :strike strike :expiry expiry :right right} q)})))
