(when-let [delay-ms (System/getenv "JUS_SPIKE_READINESS_DELAY_MS")]
  (Thread/sleep (Long/parseLong delay-ms)))

(def repl-line-delay-ms 250)

(defn- configured-line-delay-ms
  []
  (if-let [delay-ms (System/getenv "JUS_REPL_LINE_DELAY_MS")]
    (Long/parseLong delay-ms)
    repl-line-delay-ms))

(defn- pause-repl-output!
  []
  (Thread/sleep (configured-line-delay-ms)))

(defn pace-repl-startup!
  []
  (let [line-count (Long/parseLong
                    (or (System/getenv "JUS_REPL_BANNER_LINES") "0"))]
    (when (pos? line-count)
      (let [original-println (var-get #'clojure.core/println)
            remaining (atom line-count)
            after-line! (fn []
                          (flush)
                          (try
                            (pause-repl-output!)
                            (catch Throwable error
                              (alter-var-root #'clojure.core/println
                                              (constantly original-println))
                              (throw error)))
                          (when (zero? (swap! remaining dec))
                            (alter-var-root #'clojure.core/println
                                            (constantly original-println))))
            paced-println (fn [& values]
                            (apply original-println values)
                            (after-line!))]
        (alter-var-root #'clojure.core/println (constantly paced-println))))))

(defn pace-system-error-lines!
  [line-count]
  (when (pos? line-count)
    (let [original-error System/err
          remaining (atom line-count)
          after-byte! (fn [value]
                        (when (= 10 value)
                          (.flush original-error)
                          (try
                            (pause-repl-output!)
                            (catch Throwable error
                              (System/setErr original-error)
                              (throw error)))
                          (when (zero? (swap! remaining dec))
                            (System/setErr original-error))))
          paced-output (proxy [java.io.OutputStream] []
                         (write
                           ([value]
                            (.write original-error value)
                            (after-byte! value))
                           ([values offset length]
                            (dotimes [index length]
                              (let [value (bit-and
                                           0xff
                                           (aget values (+ offset index)))]
                                (.write original-error value)
                                (after-byte! value)))))
                         (flush []
                           (.flush original-error)))]
      (System/setErr (java.io.PrintStream. paced-output true)))))

(defn- pace-configured-repl-startup!
  []
  (let [line-count (Long/parseLong
                    (or (System/getenv "JUS_REPL_BANNER_LINES") "0"))]
    (case (System/getenv "JUS_REPL_BANNER_OUTPUT")
      "system-error" (pace-system-error-lines! line-count)
      (pace-repl-startup!))))

(defn stop-launch-spinner!
  []
  (let [control-script (System/getenv "JUS_SPINNER_CONTROL")
        exit-code (-> (ProcessBuilder. ^java.util.List ["sh" control-script "stop"])
                      (.inheritIO)
                      (.start)
                      (.waitFor))]
    (when-not (zero? exit-code)
      (throw (ex-info "Unable to stop the launch spinner"
                      {:exit-code exit-code})))
    (println "REPL Started. Ctrl-D to quit.")
    (flush)
    (pause-repl-output!)
    (pace-configured-repl-startup!)))
