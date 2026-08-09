(require 'rebel-readline.main)
(load-file (System/getenv "JUS_SPINNER_INIT"))
(stop-launch-spinner!)
(println (str "Clojure " (clojure-version)))
