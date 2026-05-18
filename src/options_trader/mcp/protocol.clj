(ns options-trader.mcp.protocol
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:const jsonrpc-version "2.0")

(defn parse-message [line]
  (try
    (let [result (json/parse-string line true)]
      (or result {:parse-error true :message "empty or null input"}))
    (catch Exception e
      {:parse-error true :message (.getMessage e)})))

(defn encode-message [m]
  (json/generate-string m))

(defn response [id result]
  {:jsonrpc jsonrpc-version
   :id      id
   :result  result})

(defn error-response [id code message]
  {:jsonrpc jsonrpc-version
   :id      id
   :error   {:code code :message message}})

(def ^:const err-parse       -32700)
(def ^:const err-invalid-req -32600)
(def ^:const err-method-nf   -32601)
(def ^:const err-invalid-par -32602)
(def ^:const err-internal    -32603)

(defn dispatch
  "Dispatch a parsed JSON-RPC message through handler-fn.
   handler-fn receives {:method s :params m :id id} and returns a result map.
   Returns a response map (or nil for notifications)."
  [msg handler-fn]
  (cond
    (:parse-error msg)
    (error-response nil err-parse (str "Parse error: " (:message msg)))

    (not (map? msg))
    (error-response nil err-invalid-req "Invalid request")

    :else
    (let [{:keys [id method params]} msg]
      (when (some? method)
        (try
          (let [result (handler-fn {:method method
                                    :params (or params {})
                                    :id     id})]
            (when (some? id)
              (if (:error result)
                (error-response id err-internal (str (:error result)))
                (response id result))))
          (catch Exception e
            (when (some? id)
              (error-response id err-internal (.getMessage e)))))))))

(defn write-response [^java.io.PrintWriter writer msg]
  (when msg
    (.println writer (encode-message msg))
    (.flush writer)))

(defn stdio-loop
  "Read newline-delimited JSON-RPC from rdr, dispatch via handler-fn,
   write responses to wtr. Runs until EOF or :stop signal."
  [^java.io.BufferedReader rdr ^java.io.PrintWriter wtr handler-fn]
  (loop []
    (when-let [line (try (.readLine rdr) (catch Exception _ nil))]
      (when-not (str/blank? line)
        (let [msg  (parse-message line)
              resp (dispatch msg handler-fn)]
          (write-response wtr resp)))
      (recur))))
