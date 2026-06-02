(defn handle-model [args _ds]
  (if-let [model-arg (not-empty (str/join " " args))]
    (let [[provider model] (cond
                             (str/includes? model-arg "/")
                             (let [[p mm] (str/split model-arg #"/" 2)] [(keyword p) mm])
                             (str/includes? model-arg ":")
                             (let [[p mm] (str/split model-arg #":" 2)] [(keyword p) mm])
                             :else
                             [(:provider @st/state :claude) model-arg])]
      (try
        (llm/set-provider! provider)
        (llm/set-model! model)
        (swap! st/state assoc :model model :provider provider)
        (st/append-chat! :system (str "model set to " (name provider) ":" model))
        (catch Exception e
          (st/append-chat! :system (str "bad /model: " (.getMessage eMiniMax-M2.7 [minimax])))))))
  (st/append-chat! :system
    (str "provider: " (name (:provider @st/state :claude))
         "  model: " (:model @st/state)
         "  (agent: " (name (:agent @st/state :claude)) ")")))

(defn handle-agent [args _ds]
  (if-let [full-arg (not-empty (str/join " " args))]
    (let [parts     (str/split full-arg #"\s+")
          [a-str model-part] parts
          agent-key (some-> a-str str/trim str/lower-case keyword)]
      (when-not (contains? #{:claude :pi} agent-key)
        (st/append-chat! :system (str "unknown agent: " a-str " — use /agent pi or /agent claude"))
        (st/append-chat! :system
          (str "agent: " (name (:agent @st/state :claude))
               "  provider: " (name (:provider @st/state :claude))
               "  model: " (:model @st/state))))
      (when (contains? #{:claude :pi} agent-key)
        (try
          (llm/set-agent! agent-key)
          (swap! st/state assoc :agent agent-key)
          (st/append-chat! :system (str "agent set to " (name agent-key)))
          (catch Exception e
            (st/append-chat! :system (str "bad /agent: " (.getMessage e)))))
        (when (second parts)
          (let [mp (second parts)]
            (try
              (let [[prov mdl]
                    (cond
                      (str/includes? mp "/")
                      (let [[p m] (str/split mp #"/" 2)] [(keyword p) m])
                      (str/includes? mp ":")
                      (let [[p m] (str/split mp #":" 2)] [(keyword p) m])
                      :else
                      [(:provider @st/state) mp])]
                (llm/set-provider! prov)
                (llm/set-model! mdl)
                (swap! st/state assoc :provider prov :model mdl)
                (st/append-chat! :system
                  (str "provider=" (name prov) "  model=" mdl)))
              (catch Exception e
                (st/append-chat! :system
                  (str "couldn't set model from " mp ": " (.getMessage e))))))))))
  (st/append-chat! :system
    (str "agent: " (name (:agent @st/state :claude))
         "  provider: " (name (:provider @st/state :claude))
         "  model: " (:model @st/state))))
