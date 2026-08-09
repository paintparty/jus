(require 'cljs.repl)
(load-file (System/getenv "JUS_SPINNER_INIT"))

(let [repl-title (var-get #'cljs.repl/repl-title)]
  (alter-var-root
   #'cljs.repl/repl-title
   (constantly
    (fn []
      (stop-launch-spinner!)
      (repl-title)))))
