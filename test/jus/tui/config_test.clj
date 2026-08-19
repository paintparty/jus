(ns jus.tui.config-test
  (:require [babashka.fs :as fs]
            [jus.tui.config :as config]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir! []
  (Files/createTempDirectory
   "jus-config-test-"
   (make-array FileAttribute 0)))

(defn- delete-tree! [path]
  (when (Files/exists path
                      (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
    (with-open [paths (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
      (doseq [entry (sort-by #(.getNameCount ^Path %) >
                             (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists entry)))))

(defn- config-data
  ([]
   (config-data "Jane Developer"))
  ([developer]
   (config/project-config
    {:groups ['io.github.example 'org.example]
     :developers [developer "Example Company, LLC"]
     :parent-dirs ["/tmp/projects" "/tmp/projects with spaces"]})))

(defn- creation-result! [path data]
  (try
    (config/create-config! path data)
    :created
    (catch clojure.lang.ExceptionInfo error
      (:type (ex-data error)))))

(deftype BlockingEdnValue [started proceed])

(defmethod print-method BlockingEdnValue [value writer]
  (deliver (.-started value) true)
  @(.-proceed value)
  (.write writer "\"Late Developer\""))

(deftest global-path-is-resolved-under-the-xdg-config-home
  (testing "XDG_CONFIG_HOME"
    (is (= "/tmp/custom-config/jus/config.edn"
           (str (config/global-config-path "/tmp/custom-config"
                                           "/home/example")))))
  (testing "default user config home"
    (is (= "/home/example/.config/jus/config.edn"
           (str (config/global-config-path nil "/home/example"))))))

(deftest config-format-is-commented-deterministic-readable-edn
  (let [data     (config-data "Jane \"JJ\" Developer")
        expected (str
                  "{:tui\n"
                  " {;; Values used to populate the new project wizard pickers.\n"
                  "  :projects\n"
                  "  {\n"
                  "  ;; Groups commonly used to publish projects.\n"
                  "  ;; Typically written as a reverse-DNS groupID.\n"
                  "  ;; Combined with the project name to form an identity such as org.foo/my-lib.\n"
                  "  :groups        [io.github.example\n"
                  "                  org.example]\n\n"
                  "  ;; People or organizations credited in generated project metadata.\n"
                  "  :developers    [\"Jane \\\"JJ\\\" Developer\"\n"
                  "                  \"Example Company, LLC\"]\n\n"
                  "  ;; Directories where new projects typically live.\n"
                  "  :parent-dirs   [\"/tmp/projects\"\n"
                  "                  \"/tmp/projects with spaces\"]}}}\n")
        formatted (config/format-config data)]
    (is (= expected formatted))
    (is (= data (edn/read-string formatted)))
    (is (not (.endsWith formatted "\n\n")))))

(deftest config-preview-closes-on-the-last-field
  (is (= (str "{:groups [asdf]\n"
              " :developers [\"adsf\"]\n"
              " :parent-dirs [\"/Users/jc\"]}\n")
         (config/format-config-preview
          (config/project-config
           {:groups ['asdf]
            :developers ["adsf"]
            :parent-dirs ["/Users/jc"]})))))

(deftest config-create-load-and-missing-load-use-explicit-paths
  (let [root   (temp-dir!)
        target (.resolve root "nested/config.edn")]
    (try
      (is (= {} (config/load-config target)))
      (is (not (config/config-exists? target)))
      (is (= target (config/create-config! target (config-data))))
      (is (config/config-exists? target))
      (is (= (config-data) (config/load-config target)))
      (is (Files/isDirectory (.getParent target)
                             (make-array LinkOption 0)))
      (finally
        (delete-tree! root)))))

(deftest legacy-flat-config-is-normalized-on-load
  (let [root   (temp-dir!)
        target (.resolve root "legacy.edn")]
    (try
      (spit (str target)
            (pr-str {:groups ['io.github.example]
                     :developers ["Jane Developer"]
                     :parent-dirs ["/tmp/projects"]}))
      (is (= (config/project-config
              {:groups ['io.github.example]
               :developers ["Jane Developer"]
               :parent-dirs ["/tmp/projects"]})
             (config/load-config target)))
      (finally
        (delete-tree! root)))))

(deftest malformed-config-loads-as-empty-preferences-with-error-details
  (let [root   (temp-dir!)
        target (.resolve root "config.edn")]
    (try
      (spit (str target) "{:groups [:]}")
      (let [{:keys [config error location]} (config/load-config-result target)
            output (java.io.StringWriter.)]
        (is (= {} config))
        (is (instance? RuntimeException error))
        (binding [*err* output]
          (config/report-config-load-error! target error location))
        (let [warning (str output)]
          (is (.contains warning "----- ERROR (Caught)"))
          (is (< (.indexOf warning "Type:") (.indexOf warning "Unable to load")))
          (is (.contains warning (str "Unable to load " target)))
          (is (.contains warning "Possible cause: The EDN is malformed and can't be parsed."))
          (is (.contains warning "The project wizard will be launched as if no config existed."))
          (is (.contains warning "Type:     java.lang.RuntimeException"))
          (is (.contains warning "Message:  Invalid token: :"))
          (is (re-find #"Location: .+config\.clj:\d+:\d+" warning))))
      (finally
        (delete-tree! root)))))

(deftest config-create-preserves-existing-files-and-symbolic-links
  (let [root     (temp-dir!)
        regular  (.resolve root "regular/config.edn")
        external (.resolve root "external.edn")
        link     (.resolve root "linked/config.edn")]
    (try
      (Files/createDirectories (.getParent regular)
                               (make-array FileAttribute 0))
      (spit (str regular) "keep regular")
      (is (= :config-already-exists
             (creation-result! regular (config-data))))
      (is (= "keep regular" (slurp (str regular))))
      (is (= ["config.edn"]
             (mapv #(str (.getFileName ^Path %))
                   (fs/list-dir (.getParent regular)))))

      (spit (str external) "keep linked target")
      (Files/createDirectories (.getParent link)
                               (make-array FileAttribute 0))
      (Files/createSymbolicLink link external
                                (make-array FileAttribute 0))
      (is (config/config-exists? link))
      (is (= :config-already-exists
             (creation-result! link (config-data))))
      (is (Files/isSymbolicLink link))
      (is (= external (Files/readSymbolicLink link)))
      (is (= "keep linked target" (slurp (str external))))
      (is (= ["config.edn"]
             (mapv #(str (.getFileName ^Path %))
                   (fs/list-dir (.getParent link)))))
      (finally
        (delete-tree! root)))))

(deftest concurrent-creates-allow-one-writer-without-temporary-files
  (let [root    (temp-dir!)
        target  (.resolve root "race/config.edn")
        start   (promise)
        configs (mapv #(config-data (str "Developer " %)) (range 12))
        writers (mapv (fn [data]
                        (future @start (creation-result! target data)))
                      configs)]
    (try
      (deliver start true)
      (let [results (frequencies (mapv deref writers))]
        (is (= 1 (get results :created)))
        (is (= 11 (get results :config-already-exists)))
        (is (contains? (set configs) (config/load-config target)))
        (is (= ["config.edn"]
               (mapv #(str (.getFileName ^Path %))
                     (fs/list-dir (.getParent target))))))
      (finally
        (delete-tree! root)))))

(deftest config-create-refuses-a-destination-created-during-the-write
  (let [root    (temp-dir!)
        target  (.resolve root "config.edn")
        started (promise)
        proceed (promise)
        data    (assoc-in (config-data)
                          [:tui :projects :developers]
                          [(BlockingEdnValue. started proceed)])
        writer  (future (creation-result! target data))]
    (try
      (is (= true (deref started 5000 ::timeout))
          "config formatting should reach the blocking value")
      (spit (str target) "late destination")
      (deliver proceed true)
      (is (= :config-already-exists (deref writer 5000 ::timeout)))
      (is (= "late destination" (slurp (str target))))
      (is (= ["config.edn"]
             (mapv #(str (.getFileName ^Path %))
                   (fs/list-dir root))))
      (finally
        (deliver proceed true)
        (delete-tree! root)))))

(deftest config-create-cleans-temporary-file-after-generic-publish-failure
  (let [root   (temp-dir!)
        target (.resolve root (apply str (repeat 300 "x")))]
    (try
      (is (thrown? Exception
                   (config/create-config! target (config-data))))
      (is (empty? (fs/list-dir root)))
      (finally
        (delete-tree! root)))))
