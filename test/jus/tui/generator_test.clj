(ns jus.tui.generator-test
  (:require [jus.tui.generator :as generator]
            [jus.tui.readme :as readme]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(defn- generator-request [template target]
  {:template   template
   :name       (str "io.github.jus/generator-" (name template))
   :target-dir target
   :developer  "jus tests"
   :license/id "MIT"
   :build      :bb})

(defn- temp-dir []
  (str (Files/createTempDirectory
        "jus-generator-test-"
        (make-array FileAttribute 0))))

(defn- file-exists? [path]
  (.exists (java.io.File. path)))

(deftest command-uses-the-pinned-deps-new-exec-function
  (let [request (assoc (generator-request :lib "/tmp/a project")
                       :description "quotes \" stay data; $HOME stays data")
        command (generator/command request)
        config-data (generator/config-data request)]
    (is (= ["clojure" "-Srepro" "-X:jus/generate"] command))
    (is (not-any? #(str/includes? % "\"") command))
    (is (= '{io.github.seancorfield/deps-new
             {:git/tag "v0.14.1"
              :git/sha "39d257c"}}
           (:deps config-data)))
    (is (= 'org.corfield.new/lib
           (get-in config-data [:aliases :jus/generate :exec-fn])))
    (is (= {:name        "io.github.jus/generator-lib"
            :target-dir  "/tmp/a project"
            :developer   "jus tests"
            :description "quotes \" stay data; $HOME stays data"
            :license/id  "MIT"
            :build       :bb}
           (get-in config-data [:aliases :jus/generate :exec-args])))))

(deftest command-omits-a-blank-description
  (let [config-data (generator/config-data
                     (assoc (generator-request :app "/tmp/example")
                            :description "  "))]
    (is (not (contains? (get-in config-data
                                [:aliases :jus/generate :exec-args])
                        :description)))))

(deftest command-omits-an-absent-or-blank-developer
  (let [exec-args #(get-in (generator/config-data %)
                           [:aliases :jus/generate :exec-args])
        without-developer (exec-args
                           (dissoc (generator-request :app "/tmp/example")
                                   :developer))
        blank-developer (exec-args
                         (assoc (generator-request :app "/tmp/example")
                                :developer "  "))]
    (is (not (contains? without-developer :developer)))
    (is (not (contains? blank-developer :developer)))))

(deftest command-supports-project-rooted-source-options
  (let [config-data (generator/config-data
                     (assoc (generator-request :lib "/tmp/example")
                            :top "example"
                            :main "core"))
        exec-args (get-in config-data [:aliases :jus/generate :exec-args])]
    (is (= "example" (:top exec-args)))
    (is (= "core" (:main exec-args)))))

(deftest command-rejects-options-outside-the-frozen-contract
  (let [request (generator-request :lib "/tmp/example")]
    (testing "unsupported project templates"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"must be lib or app"
                            (generator/command (assoc request
                                                      :template :scratch)))))
    (testing "overwriting an existing target"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"must not overwrite"
                            (generator/command (assoc request
                                                      :overwrite :delete)))))
    (testing "unknown deps-new options"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"unknown options"
                            (generator/command (assoc request
                                                      :test-runner :lazytest)))))))

(defn- generated-project-result [template]
  (let [parent (temp-dir)
        target (str parent "/" (name template))]
    (try
      (let [result (-> (generator-request template target)
                       generator/start!
                       generator/await!)]
        {:result    result
         :target    (file-exists? target)
         :bb-edn    (file-exists? (str target "/bb.edn"))
         :bb-content (slurp (str target "/bb.edn"))
         :readme (slurp (str target "/README.md"))
         :git-doc (when (file-exists? (str target "/docs/git-deps.md"))
                    (slurp (str target "/docs/git-deps.md")))
         :git-template (when (file-exists? (str target "/docs/git-deps.template.md"))
                         (slurp (str target "/docs/git-deps.template.md")))
         :source    (file-exists?
                     (str target
                          "/src/jus/generator_"
                          (name template)
                          ".clj"))})
      (finally
        (generator/cleanup! parent)))))

(deftest pinned-deps-new-creates-isolated-library-and-application-projects
  (doseq [template [:lib :app]]
    (testing (name template)
      (let [{:keys [result target bb-edn bb-content source readme git-doc git-template]}
            (generated-project-result template)]
        (is (= 0 (:exit-code result)))
        (is (str/includes? (:out result) "Creating project from"))
        (is target)
        (is bb-edn)
        (is (str/includes? bb-content "\n :min-bb-version\n \"1.13.222\"\n"))
        (is (str/includes? bb-content "\n {:requires\n  [build]\n"))
        (is (str/includes? bb-content "\n\n  test\n  {:doc  "))
        (is (not (re-find #"(?m)^\s*:require\s" bb-content)))
        (is source)
        (if (= :lib template)
          (do
            (is (str/starts-with? readme "# generator-lib\n\n**FIXME: my new library.**<br>"))
            (is (str/includes? readme "io.github.jus/generator-lib {:mvn/version \"0.1.0\"}"))
            (is (str/includes? readme "(require '[jus.generator-lib])"))
            (is (str/includes? readme "## Quick comparison (FIX ME)"))
            (is (str/includes? readme "jus tests"))
            (is (str/includes? readme "[MIT License]"))
            (is (str/includes? bb-content "git-deps-docs"))
            (is (str/includes? git-doc "No release has been tagged yet"))
            (is (str/includes? git-doc "https://github.com/jus/generator-lib"))
            (is (str/includes? git-doc "io.github.jus/generator-lib"))
            (is (str/includes? git-template "{{TAG}}"))
            (is (str/includes? git-template "{{SHA}}"))
            (is (not (str/includes? git-template "{{PROJECT")))
            (is (not (str/includes? readme "Eclipse Public License")))
            (is (not (re-find #"\{\{[A-Z_]+\}\}" readme))))
          (do
            (is (str/includes? readme "Run the project directly, via `:exec-fn`:"))
            (is (nil? git-doc))
            (is (nil? git-template))
            (is (not (str/includes? bb-content "git-deps-docs")))
            (is (not (str/includes? readme "## Quick comparison (FIX ME)")))))))))

(deftest library-git-dependency-docs-refresh-from-real-release-tags
  (let [parent (temp-dir)
        target (str parent "/a library")
        shell (fn [& args] (apply process/shell {:dir target :out :string :err :string} args))
        git (fn [& args]
              (apply shell "git" "-c" "user.name=Release Test" "-c" "user.email=release@example.invalid"
                     "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args))]
    (try
      (is (= 0 (:exit-code (-> (generator-request :lib target) generator/start! generator/await!))))
      (git "init" "-q")
      (let [original (slurp (str target "/docs/git-deps.md"))
            result @(process/process ["bb" "git-deps-docs"] {:dir target :out :string :err :string})]
        (is (not (zero? (:exit result))))
        (is (= original (slurp (str target "/docs/git-deps.md")))))
      (git "add" ".")
      (git "commit" "-qm" "Initial release fixture")
      (git "tag" "-am" "First release" "v0.1.0")
      (let [sha (str/trim (:out (git "rev-parse" "HEAD")))]
        (spit (str target "/later.txt") "An untagged commit")
        (git "add" "later.txt")
        (git "commit" "-qm" "Later fixture commit")
        (shell "bb" "git-deps-docs")
        (let [doc (slurp (str target "/docs/git-deps.md"))]
          (is (str/includes? doc ":git/tag \"v0.1.0\""))
          (is (str/includes? doc (str ":git/sha " (pr-str sha))))
          (is (not (str/includes? doc "No release has been tagged yet")))
          (is (not (str/includes? doc "{{SHA}}"))))
        (git "tag" "v0.2.0")
        (shell "bb" "git-deps-docs")
        (is (str/includes? (slurp (str target "/docs/git-deps.md")) ":git/tag \"v0.2.0\""))
        (shell "bb" "git-deps-docs" "v0.1.0")
        (is (str/includes? (slurp (str target "/docs/git-deps.md")) (str ":git/sha " (pr-str sha))))
        (let [before (slurp (str target "/docs/git-deps.md"))
              result @(process/process ["bb" "git-deps-docs" "missing-tag"]
                                        {:dir target :out :string :err :string})]
          (is (not (zero? (:exit result))))
          (is (= before (slurp (str target "/docs/git-deps.md"))))))
      (finally
        (generator/cleanup! parent)))))

(deftest library-readme-uses-generated-defaults-and-literal-user-text
  (let [target (temp-dir)]
    (try
      (.mkdirs (java.io.File. target "src/example"))
      (spit (str target "/build.clj") "(ns build)\n(def lib 'net.clojars.example/sample)\n(defn pom-template [] [[:url \"https://github.com/example/sample\"]])\n")
      (spit (str target "/src/example/core.clj") "(ns example.core)\n")
      (spit (str target "/README.md")
            "# example/sample\n\nDefault description.\n\n## License\n\nCopyright © 2026 Default Developer\n\nDistributed under the [MIT License](https://opensource.org/licenses/MIT)\n")
      (readme/write-library-readme! {:name "example/sample" :target-dir target})
      (let [rendered (slurp (str target "/README.md"))]
        (is (str/starts-with? rendered "# sample\n\n**Default description.**<br>"))
        (is (str/includes? rendered "net.clojars.example/sample"))
        (is (str/includes? rendered "(require '[example.core])"))
        (is (str/includes? rendered "Copyright © 2026 Default Developer"))
        (is (re-find #"2026 [A-Z][a-z]+\s+\| Experimental" rendered)))
      (readme/write-library-readme! {:name "example/sample" :target-dir target
                                   :description "Literal {{YEAR}}, $HOME, and \\ paths"})
      (is (str/includes? (slurp (str target "/README.md"))
                         "**Literal {{YEAR}}, $HOME, and \\ paths**<br>"))
      (finally
        (generator/cleanup! target)))))

(deftest bb-edn-is-laid-out-in-a-single-column
  (let [source "{:paths [\"src\"]\n ;; note\n :deps {a/b {:mvn/version \"1\"}}\n :tasks {:requires [[x :as y]]\n  t {:doc \"d\" ; trailing\n     :task (run)}\n  u {:task (run)}}}"
        laid-out (#'generator/single-column-bb-edn source)]
    (is (= (read-string source) (read-string laid-out)))
    (is (= "{:paths
 [\"src\"]

 ;; note
 :deps
 {a/b {:mvn/version \"1\"}}

 :tasks
 {:requires
  [[x :as y]]

  t
  {:doc \"d\" ; trailing
     :task (run)}

  u
  {:task (run)}}}"
           laid-out))))

(deftest project-rooted-options-create-a-project-rooted-source-namespace
  (let [parent (temp-dir)
        target (str parent "/example")]
    (try
      (let [result (-> (generator-request :lib target)
                       (assoc :top "example" :main "core")
                       generator/start!
                       generator/await!)
            source (str target "/src/example/core.clj")]
        (is (= 0 (:exit-code result)))
        (is (file-exists? source))
        (is (str/includes? (slurp source) "(ns example.core"))
        (is (str/includes? (slurp (str target "/README.md")) "(require '[example.core])")))
      (finally
        (generator/cleanup! parent)))))

(deftest generator-failures-report-captured-output-without-overwriting
  (let [parent   (temp-dir)
        target   (str parent "/existing")
        sentinel (str target "/keep.txt")]
    (try
      (.mkdirs (java.io.File. target))
      (spit sentinel "keep")
      (let [result (-> (generator-request :lib target)
                       generator/start!
                       generator/await!)]
        (is (= 1 (:exit-code result)))
        (is (empty? (:out result)))
        (is (str/includes? (:err result) "already exists"))
        (is (= "keep" (slurp sentinel))))
      (finally
        (generator/cleanup! parent)))))

(deftest running-generator-processes-can-be-cancelled
  (let [parent (temp-dir)
        target (str parent "/cancelled")]
    (try
      (let [process (generator/start! (generator-request :app target))
            config-dir (str (::generator/config-dir process))
            completion (future (generator/await! process))]
        (generator/cancel! process)
        (is (map? @completion))
        (is (not (file-exists? config-dir)))
        (generator/cleanup! target)
        (is (not (file-exists? target))))
      (finally
        (generator/cleanup! parent)))))

(deftest cleanup-removes-only-the-target-without-following-links
  (let [parent        (temp-dir)
        target        (str parent "/target")
        nested        (str target "/nested")
        sibling       (str parent "/keep.txt")
        external      (str parent "/external")
        external-file (str external "/outside.txt")
        link           (str target "/external-link")]
    (try
      (.mkdirs (java.io.File. nested))
      (.mkdirs (java.io.File. external))
      (spit (str nested "/partial.txt") "partial")
      (spit sibling "keep")
      (spit external-file "outside")
      (Files/createSymbolicLink
       (Path/of link (make-array String 0))
       (Path/of external (make-array String 0))
       (make-array FileAttribute 0))
      (generator/cleanup! target)
      (is (not (file-exists? target)))
      (is (= "keep" (slurp sibling)))
      (is (= "outside" (slurp external-file)))
      (finally
        (generator/cleanup! parent)))))
