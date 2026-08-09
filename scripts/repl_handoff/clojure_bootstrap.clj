(load-file (System/getenv "JUS_SPINNER_INIT"))
(stop-launch-spinner!)
(clojure.main/main "-r")
