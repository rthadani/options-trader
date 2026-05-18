(ns options-trader.screener.nl
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [options-trader.tui.llm :as llm]))

(defn- indicator-meanings
  "Map of column-keyword → human-readable indicator meaning, derived from
   resources/indicators.edn. Keys are kebab-style column names matching DuckDB."
  []
  (let [cfg (edn/read-string (slurp (io/resource "indicators.edn")))]
    (into {}
          (for [spec (concat (:indicators cfg) (:composites cfg))
                :let [col (some-> spec :column name)
                      kind (some-> spec :kind name)
                      params (:params spec)]
                :when col]
            [col (if (seq params)
                   (format "%s%s" kind (pr-str (vec params)))
                   kind)]))))

(defn schema-summary
  "Returns a string describing the columns available on latest_indicators,
   annotated with each indicator's kind + params where known."
  [ds]
  (let [cols     (jdbc/execute! ds ["PRAGMA table_info('latest_indicators')"]
                                {:builder-fn rs/as-unqualified-lower-maps})
        meanings (indicator-meanings)
        lines    (for [{:keys [name type]} cols
                       :let [m (get meanings name)]]
                   (if m
                     (format "  %s %s  -- %s" name type m)
                     (format "  %s %s" name type)))]
    (str "Table latest_indicators (one row per symbol):\n"
         (str/join "\n" lines))))

(def ^:private sql-prompt-template
  (str "You are generating DuckDB SQL for a stock screener.\n\n"
       "%s\n\n"
       "Screen description:\n%s\n\n"
       "Write a single SELECT statement against latest_indicators that returns "
       "matching symbols (and any columns that justify the match). Use AND / OR "
       "/ BETWEEN as needed. Default LIMIT 25 unless the description specifies "
       "otherwise. ORDER BY a column that ranks the strongest matches first.\n\n"
       "Output rules:\n"
       "- Respond with raw SQL only — no preamble, no markdown fences, no commentary.\n"
       "- Single statement, no trailing semicolon.\n"
       "- If the description references an indicator that is NOT in the schema "
       "above, respond with exactly: MISSING <column-name>"))

(defn description->sql
  "Translate a natural-language screen description into a DuckDB SQL string
   using the available schema. Returns one of:
     {:sql \"SELECT …\"}
     {:missing \"col-name\"}
     {:error \"…\"}
   opts may contain :model and :sh-fn (forwarded to llm/complete)."
  ([ds description] (description->sql ds description {}))
  ([ds description opts]
   (try
     (let [prompt (format sql-prompt-template (schema-summary ds) description)
           out    (-> (llm/complete opts prompt)
                      (str/replace #"(?s)```[a-z]*\n?" "")
                      (str/replace #"```" "")
                      str/trim)]
       (cond
         (str/blank? out)          {:error "empty response from llm"}
         (str/starts-with? out "MISSING ")
         {:missing (-> out (subs 8) str/trim)}

         :else                     {:sql out}))
     (catch Exception e
       {:error (.getMessage e)}))))
