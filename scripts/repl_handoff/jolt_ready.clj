(require '[clojure.string :as str])

(letfn [(shell-quote [value]
          (str "'" (str/replace value "'" "'\"'\"'") "'"))]
  (let [control-script (System/getenv "JUS_SPINNER_CONTROL")]
    (jolt.host/sh (str "sh " (shell-quote control-script) " stop"))))

(println "REPL Started. Ctrl-D to quit.")
((ns-resolve 'jolt.main '-main) "repl")
