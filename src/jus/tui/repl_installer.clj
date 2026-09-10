(ns jus.tui.repl-installer
  "Cancellable in-1 installation. Only this boundary starts installer processes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jus.tui.repls :as repls])
  (:import (java.nio.file Files)
           (java.util.concurrent TimeUnit)))

(def bootstrap-url "https://in-1.cc")
(def diagnostic-limit 12000)

(defn clean-diagnostics
  [text]
  (-> (str text)
      (str/replace #"\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)" "")
      (str/replace #"\u001b\[[0-?]*[ -/]*[@-~]" "")
      (str/replace #"[\p{Cntrl}&&[^\n\t]]" "")))

(defn- capture-tail!
  [stream output]
  (with-open [reader (io/reader stream)]
    (let [buffer (char-array 4096)]
      (loop []
        (let [n (.read reader buffer)]
          (when (pos? n)
            (swap! output (fn [previous]
                            (let [s (str previous (String. buffer 0 n))]
                              (subs s (max 0 (- (count s) diagnostic-limit))))))
            (recur)))))))

(defn- process-descendants
  [process]
  (with-open [stream (.descendants (.toHandle process))]
    (vec (iterator-seq (.iterator stream)))))

(defn- stop-tree!
  [process known]
  (let [handles (distinct (concat (process-descendants process) known))]
    ;; Stop the parent from starting new work before killing its descendants.
    (.destroyForcibly process)
    (doseq [handle (reverse (vec handles))]
      (when (.isAlive handle) (.destroyForcibly handle)))
    (.waitFor process)))

(defn- run-command!
  [{:keys [cancelled? process-lock active output environment]} command]
  (let [command (assoc (vec command) 0
                       (or (repls/find-on-path (first command) {:path (get environment "PATH")})
                           (first command)))
        process (locking process-lock
                  (when @cancelled?
                    (throw (ex-info "Installation cancelled" {:cancelled? true})))
                  (let [builder (doto (ProcessBuilder. ^java.util.List command)
                                  (.redirectErrorStream true))]
                    (doseq [[k v] environment] (.put (.environment builder) k v))
                    (let [p (.start builder)]
                      (.close (.getOutputStream p))
                      (reset! active p)
                      p)))
        reader (future (capture-tail! (.getInputStream process) output))]
    (try
      (loop [known #{}]
        (let [known (into known (process-descendants process))]
          (cond
            @cancelled? (do (stop-tree! process known)
                            (throw (ex-info "Installation cancelled" {:cancelled? true})))
            (.waitFor process 50 TimeUnit/MILLISECONDS)
            (if (realized? reader)
              (do @reader (.exitValue process))
              (do (Thread/sleep 50) (recur known)))
            :else (recur known))))
      (finally
        (reset! active nil)
        (when (.isAlive process) (stop-tree! process []))))))

(defn- checked-command!
  [handle command]
  (let [exit-code (run-command! handle command)]
    (when-not (zero? exit-code)
      (throw (ex-info "Installation command failed" {:exit-code exit-code})))))

(defn- preflight!
  [handle env]
  (doseq [tool ["bash" "git" "curl" "make"]]
    (when-not (repls/find-on-path tool env)
      (throw (ex-info (str "Required executable not found: " tool
                           ". Install Bash, Git, curl and GNU make, then retry.") {}))))
  (checked-command! handle ["make" "--version"])
  (when-not (str/includes? @(:output handle) "GNU Make")
    (throw (ex-info "GNU make is required. Put GNU make on PATH as make, then retry." {})))
  (reset! (:output handle) ""))

(defn installation-command
  "Fixed shell program; all variable values travel as separate argv entries."
  [bootstrap runtime mode env]
  ["bash" "--noprofile" "--norc" "-c"
   "script=$1; tool=$2; mode=$3; prefix=$4; if [ \"$mode\" = temporary ]; then source \"$script\" --temp \"$tool\"; else source \"$script\" --local \"$tool\" \"PREFIX=$prefix\"; fi"
   "jus-install" (str bootstrap) (:installer (repls/option runtime))
   (name mode) (repls/install-prefix mode env)])

(defn- install!
  [handle {:keys [runtime mode locations]}]
  (let [env (or locations (repls/environment))
        directory (Files/createTempDirectory "jus-in-1-"
                                             (make-array java.nio.file.attribute.FileAttribute 0))
        bootstrap (.toFile (.resolve directory "bootstrap.sh"))]
    (try
      (when-not (and (repls/installation-supported?)
                     (:installer (repls/option runtime))
                     (#{:temporary :persistent} mode))
        (throw (ex-info "Unsupported dialect installation request" {})))
      (preflight! handle env)
      (checked-command! handle ["curl" "--fail" "--silent" "--show-error" "--location"
                                "--proto" "=https" "--proto-redir" "=https"
                                "--connect-timeout" "30" "--max-time" "120"
                                "--output" (str bootstrap) bootstrap-url])
      (checked-command! handle (installation-command bootstrap runtime mode env))
      (let [expected (io/file (repls/install-prefix mode env) "bin"
                              (:installer (repls/option runtime)))]
        (if-let [executable (repls/executable-path expected)]
          {:status :installed :executable executable :exit-code 0}
          (throw (ex-info (str "Installer finished without an executable at " expected) {}))))
      (catch Exception error
        {:status (if @(:cancelled? handle) :cancelled :failed)
         :exit-code (:exit-code (ex-data error))
         :error (.getMessage error)
         :diagnostics (clean-diagnostics @(:output handle))})
      (finally
        (Files/deleteIfExists (.toPath bootstrap))
        (Files/deleteIfExists directory)))))

(defn start!
  "Start a worker. Optional :locations isolates HOME/TMPDIR/PATH for integration tests."
  [{:keys [locations] :as request}]
  (let [env (or locations (repls/environment))
        handle {:cancelled? (atom false) :process-lock (Object.) :active (atom nil)
                :output (atom "")
                :environment {"HOME" (:home env) "TMPDIR" (:tmp env) "PATH" (:path env)}}]
    (assoc handle :completion (future (install! handle request)))))

(defn await!
  [handle]
  @(:completion handle))

(defn cancel!
  "Signal cancellation; await! completes after process cleanup. Safe to repeat."
  [handle]
  (locking (:process-lock handle)
    (reset! (:cancelled? handle) true)))
