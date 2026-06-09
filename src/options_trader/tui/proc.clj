(ns options-trader.tui.proc
  "ProcessBuilder gateway. Subprocess env inherits the parent (PATH/HOME)
   then applies overrides — clojure.java.shell/sh replaces env wholesale,
   so we drop to ProcessBuilder. Centralised so the JDK-internal
   ProcessEnvironment$StringEnvironment type hints stay in one place.")

(defn spawn!
  "Start a subprocess with merged env vars and return the Process.

     :cmd          (required) vector of strings — command + args
     :cwd          (required) working dir (string or java.io.File)
     :env          map of String→String overrides — merged onto inherited parent env
     :merge-err?   true → stderr piped into stdout; false → kept separate (default true)"
  ^Process
  [{:keys [cmd cwd env merge-err?] :or {merge-err? true}}]
  (let [pb (ProcessBuilder. ^java.util.List cmd)]
    (.directory pb (if (instance? java.io.File cwd)
                     cwd
                     (java.io.File. ^String (str cwd))))
    (when (seq env)
      (let [^java.util.Map env-map (.environment pb)]
        (doseq [[k v] env]
          (.put env-map (str k) (str v)))))
    (.redirectErrorStream pb (boolean merge-err?))
    (.start pb)))

(defn one-shot!
  "Spawn, write `input` on stdin, slurp stdout, return {:stdout :exit}.
   Convenience for the synchronous CLI-RPC pattern (claude --print, pi -p).
   :merge-err? defaults to true so stderr appears inline in :stdout."
  [{:keys [input] :as opts}]
  (let [proc   (spawn! opts)
        writer (java.io.PrintWriter. (.getOutputStream proc) true)]
    (.println writer (str (or input "")))
    (.close writer)
    {:stdout (slurp (.getInputStream proc))
     :exit   (.waitFor proc)}))
