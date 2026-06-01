(ns options-trader.actions.indicators
  "Action handler for proposing and adding indicator specs to indicators.edn.
   Triggered when a screen's description references a column that is not yet
   present on latest_indicators."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [options-trader.actions.core :as actions]
            [options-trader.indicators.engine :as engine]
            [options-trader.indicators.ta4j :as ta4j]
            [options-trader.paths :as paths]
            [options-trader.tui.llm :as llm]))

(defn- read-config [] (engine/load-config))

(defn- already-present? [col]
  (let [cfg (read-config)
        col-kw (keyword col)]
    (boolean
      (some #(= col-kw (:column %))
            (concat (:indicators cfg) (:composites cfg))))))

(def ^:private proposal-prompt
  (str "You are helping extend an indicator schema. The user's screen description "
       "referenced a column that is not yet computed: %s\n\n"
       "Propose a single indicator spec from this fixed list of supported kinds:\n%s\n\n"
       "Respond with valid JSON only, shape: {\"kind\": \"<Kind>\", "
       "\"params\": [<numbers>], \"column\": \"<col-name>\", \"rationale\": \"<one line>\"}\n"
       "- kind MUST be one of the kinds above (case-sensitive)\n"
       "- params order matches the ta4j constructor (e.g. RSI takes [period], "
       "BBLower takes [period, k], KCLower takes [period, ratio, atr-period])\n"
       "- column should be the snake_case identifier the user referenced\n"
       "- If no listed kind can produce this column, respond exactly: "
       "{\"unsupported\": true, \"reason\": \"<why>\"}"))

(defn- format-kinds [kinds]
  (str/join "\n" (map #(str "  " (name %)) kinds)))

(defn propose-spec
  "Ask the LLM to suggest an indicator spec for the given column name.
   Returns {:proposal {:kind … :params [...] :column …}} or
   {:unsupported true :reason …} or {:error …}.
   opts may contain :model and :sh-fn (forwarded to llm/complete-json)."
  ([column] (propose-spec column {}))
  ([column opts]
   (try
     (let [kinds  (ta4j/known-kinds)
           prompt (format proposal-prompt column (format-kinds kinds))
           res    (llm/complete-json opts prompt)]
       (cond
         (:unsupported res) res
         (and (:kind res) (:column res))
         {:proposal {:kind   (keyword (:kind res))
                     :params (vec (:params res))
                     :column (keyword (:column res))
                     :rationale (:rationale res)}}
         :else {:error (str "malformed proposal: " (pr-str res))}))
     (catch Exception e
       {:error (.getMessage e)}))))

(defn- append-spec [cfg spec]
  (update cfg :indicators (fnil conj []) spec))

(defn- pretty-edn [v]
  (with-out-str (pp/pprint v)))

(defn apply-proposal!
  "Append the proposal to indicators.edn. Returns {:applied? true :spec …}
   or {:error …}. Does NOT alter the DB — the engine's next compute-many
   pass will add the column and backfill history."
  [{:keys [kind params column] :as spec}]
  (cond
    (not (and kind column))
    {:error "spec missing :kind or :column"}

    (not ((set (ta4j/known-kinds)) kind))
    {:error (str "kind not in dispatch: " kind)}

    (already-present? (name column))
    {:error (str "column already present: " (name column))}

    :else
    (let [cfg     (read-config)
          updated (append-spec cfg (cond-> {:kind kind :column column}
                                     (seq params) (assoc :params params)
                                     (not (seq params)) (assoc :params [])))]
      (let [target (io/file (paths/indicators-file))]
        (.mkdirs (.getParentFile target))
        (spit target (pretty-edn updated)))
      {:applied? true :spec spec})))

(defmethod actions/handle-action :propose-indicator
  [{:keys [column] :as action}]
  (cond
    (str/blank? column) {:error "column required" :action action}
    (already-present? column) {:error (str "column already present: " column)}
    :else (propose-spec column (select-keys action [:model :sh-fn]))))

(defmethod actions/handle-action :add-indicator
  [{:keys [spec confirm?] :as action}]
  (cond
    (not (map? spec))
    {:error "spec map required" :action action}

    (not confirm?)
    {:preview spec
     :message (str "Will append " (name (:kind spec)) " " (pr-str (:params spec))
                   " → column " (name (:column spec)) " to indicators.edn. "
                   "Re-call with :confirm? true to apply.")}

    :else
    (apply-proposal! spec)))
