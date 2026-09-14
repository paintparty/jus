(ns jus.tui.installer
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jus.tui.repls :as repls]))

(def executable-tools
  {"clojure" "clojure", "bb" "babashka", "node" "node"
   "jolt" "jolt", "lg" "let-go"})

(defn cache-directory []
  (.getCanonicalPath
   (io/file (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                (str (System/getProperty "user.home") "/.cache"))
            "jus" "in-1")))

(defn find-executable [executable path]
  (some (fn [directory]
          (let [file (io/file (if (empty? directory) "." directory) executable)]
            (when (and (.isFile file) (.canExecute file))
              (.getAbsolutePath file))))
        (str/split (or path "") #":" -1)))

(defn request
  "Describe missing commands without downloading or changing the filesystem."
  [runtime]
  (let [base (cache-directory)
        prefix (str base "/local")
        inherited (into {} (System/getenv))
        path (str (get inherited "PATH" "") ":" prefix "/bin")
        environment (assoc inherited "PATH" path)
        required (distinct (conj (repls/required-executables runtime) "bb"))
        missing (filterv #(nil? (find-executable % path)) required)
        installer (find-executable "in-1" path)]
    {:runtime runtime
     :base base
     :prefix prefix
     :environment environment
     :missing missing
     :tools (mapv executable-tools missing)
     :installer installer
     :bootstrap? (nil? installer)}))

(defn- run-process! [environment command]
  (let [builder (ProcessBuilder. ^java.util.List command)
        target (.environment builder)]
    (.clear target)
    (.putAll target environment)
    (-> builder (.inheritIO) (.start) (.waitFor))))

(defn- installation-environment [{:keys [environment base]}]
  (assoc (into {} (remove (fn [[key _]]
                           (or (= key "PREFIX")
                               (str/starts-with? key "IN1_")))
                         environment))
         "IN1_ROOT" (str base "/state")
         "IN1_CACHE" (str base "/downloads")))

(defn install!
  "Install an approved request, then verify every required command."
  [{:keys [base prefix tools installer environment runtime] :as approved}]
  (when (seq tools)
    (let [install-env (installation-environment approved)
          command (if installer
                    (into [installer "--local" (str "PREFIX=" prefix)] tools)
                    (let [bash (find-executable "bash" (get environment "PATH"))
                          script (io/resource "jus/tui/install.sh")]
                      (when-not bash
                        (throw (ex-info "Bash is required to bootstrap in-1" {})))
                      (when-not script
                        (throw (ex-info "Unable to locate the in-1 bootstrap" {})))
                      (into [bash (.getPath (io/file (.toURI script)))
                             (str "PREFIX=" prefix)] tools)))
          install-env (if installer install-env
                        (assoc install-env "TMPDIR" (str base "/bootstrap")))]
      (when-not installer
        (.mkdirs (io/file base "bootstrap")))
      (let [status (run-process! install-env command)]
        (when-not (zero? status)
          (throw (ex-info "in-1 installation failed; select the REPL to retry"
                          {:exit-code status}))))))
  (let [required (distinct (conj (repls/required-executables runtime) "bb"))
        missing (filterv #(nil? (find-executable % (get environment "PATH")))
                         required)]
    (when (seq missing)
      (throw (ex-info (str "Required executables still missing: "
                          (str/join ", " missing))
                      {:missing missing}))))
  environment)
