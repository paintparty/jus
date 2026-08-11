(ns repl-handoff.launch
  (:require [clojure.java.io :as io])
  (:import (java.lang ProcessHandle)
           (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.security MessageDigest)))

(def ^:private cljs-version "1.12.145")
(def ^:private rebel-version "0.1.11")

(def ^:private script-dir
  (-> (if-let [resource (io/resource "repl_handoff/launch.clj")]
        (io/file (.toURI resource))
        (io/file *file*))
      (.getAbsoluteFile)
      (.getParentFile)
      (.getCanonicalPath)))

(def ^:private spinner-control
  (str script-dir java.io.File/separator "spinner.sh"))

(def ^:private ready-init
  (str script-dir java.io.File/separator "ready.clj"))

(def ^:private clojure-bootstrap
  (str script-dir java.io.File/separator "clojure_bootstrap.clj"))

(def ^:private spinner-init
  (str script-dir java.io.File/separator "spinner_control.clj"))

(def ^:private rebel-ready-init
  (str script-dir java.io.File/separator "rebel_ready.clj"))

(def ^:private cljs-ready-init
  (str script-dir java.io.File/separator "cljs_ready.clj"))

(def ^:private jolt-ready-init
  (str script-dir java.io.File/separator "jolt_ready.clj"))

(def ^:private let-go-ready-init
  (str script-dir java.io.File/separator "let_go_ready.clj"))

(def runtime-labels
  {:clojure       "Clojure"
   :rebel         "Clojure with Rebel Readline"
   :babashka      "Babashka"
   :clojurescript "ClojureScript"
   :jolt          "Jolt"
   :let-go        "let-go"})

(def runtime-banner-lines
  {:clojure 1
   :rebel 2
   :babashka 2
   :clojurescript 1
   :jolt 1
   :let-go 2})

(def runtime-banner-outputs
  {:babashka :system-error})

(defn- sha-256
  [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes value StandardCharsets/UTF_8))]
    (format "%064x" (BigInteger. 1 digest))))

(defn cljs-output-dir
  [working-directory]
  (let [cache-home (or (System/getenv "XDG_CACHE_HOME")
                       (str (System/getProperty "user.home")
                            java.io.File/separator
                            ".cache"))
        canonical-directory (.getCanonicalPath (io/file working-directory))]
    (str cache-home
         java.io.File/separator "jus"
         java.io.File/separator "cljs-repl"
         java.io.File/separator cljs-version
         java.io.File/separator (sha-256 canonical-directory))))

(defn runtime-command
  [runtime working-directory]
  (case runtime
    :clojure
    ["clojure" "-M" clojure-bootstrap]

    :rebel
    ["clojure"
     "-J--enable-native-access=ALL-UNNAMED"
     "-Sdeps"
     (pr-str {:deps {'com.bhauman/rebel-readline
                     {:mvn/version rebel-version}}})
     "-M"
     "-i"
     rebel-ready-init
     "-m"
     "rebel-readline.main"
     "--color-theme"
     "neutral-screen-theme"]

    :babashka
    ["bb" "--init" ready-init "repl"]

    :clojurescript
    (let [output-dir (cljs-output-dir working-directory)]
      ["clojure"
       "-Sdeps"
       (pr-str {:deps {'org.clojure/clojurescript
                       {:mvn/version cljs-version}}})
       "-M"
       "-i"
       cljs-ready-init
       "-m"
       "cljs.main"
       "--output-dir"
       output-dir
       "--repl-env"
       "node"
       "--repl"])

    :jolt
    ["jolt" "run" jolt-ready-init]

    :let-go
    ["lg" "-r" let-go-ready-init]

    (throw (ex-info "Unknown spike runtime" {:runtime runtime}))))

(defn- await-file!
  [path]
  (loop [attempts 0]
    (cond
      (.isFile (io/file path)) true
      (< attempts 200) (do (Thread/sleep 10)
                           (recur (inc attempts)))
      :else (throw (ex-info "Spinner sidecar did not become ready"
                            {:path path})))))

(defn- stop-spinner!
  [environment]
  (let [builder (ProcessBuilder. ^java.util.List ["sh" spinner-control "stop"])
        process-environment (.environment builder)]
    (doseq [[name value] environment]
      (.put process-environment name value))
    (-> builder
        (.inheritIO)
        (.start)
        (.waitFor))))

(defn- start-spinner!
  [state-path label target-pid]
  (print (str "\r☯  Starting " label "..."))
  (flush)
  (let [spinner (-> (ProcessBuilder. ^java.util.List
                     ["sh" spinner-control "run"
                      state-path label (str target-pid)])
                    (.inheritIO)
                    (.start))]
    (await-file! (str state-path java.io.File/separator "ready"))
    (str (.pid spinner))))

(defn- exec-process!
  [environment command]
  (require '[babashka.process])
  (apply (resolve 'babashka.process/exec)
         {:extra-env environment}
         command))

(defn launch!
  [runtime]
  (let [working-directory (.getCanonicalPath (io/file "."))
        label (or (get runtime-labels runtime)
                  (throw (ex-info "Unknown spike runtime" {:runtime runtime})))
        target-pid (.pid (ProcessHandle/current))
        state-dir (.toFile (Files/createTempDirectory
                            "jus-repl-handoff-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        state-path (.getCanonicalPath state-dir)
        spinner-pid (start-spinner! state-path label target-pid)
        environment {"JUS_SPINNER_CONTROL" spinner-control
                     "JUS_SPINNER_STATE" state-path
                     "JUS_SPINNER_PID" spinner-pid
                     "JUS_SPINNER_INIT" spinner-init
                     "JUS_REPL_BANNER_LINES"
                     (str (get runtime-banner-lines runtime 0))
                     "JUS_REPL_BANNER_OUTPUT"
                     (name (get runtime-banner-outputs runtime :println))}
        _ (when (= :clojurescript runtime)
            (.mkdirs (io/file (cljs-output-dir working-directory))))
        command (runtime-command runtime working-directory)]
    (try
      (exec-process! environment command)
      (catch Exception exception
        (stop-spinner! environment)
        (throw exception)))))

(defn -main
  [& [runtime-name]]
  (when-not runtime-name
    (binding [*out* *err*]
      (println "Usage: bb -cp scripts -m repl-handoff.launch <runtime>"))
    (System/exit 2))
  (launch! (keyword runtime-name)))
