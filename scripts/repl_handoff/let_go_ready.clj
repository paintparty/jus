(require '[os :as os])

(let [{:keys [exit]} (os/sh "sh"
                            (System/getenv "JUS_SPINNER_CONTROL")
                            "stop")]
  (when-not (zero? exit)
    (throw (ex-info "Unable to stop the launch spinner"
                    {:exit-code exit}))))

(println "REPL Started. Ctrl-D to quit.")
