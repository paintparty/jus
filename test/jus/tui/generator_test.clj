(ns jus.tui.generator-test
  (:require [jus.tui.generator :as generator]
            [clojure.edn :as edn]
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

(defn- command-options [command]
  (->> (subvec command 5)
       (partition 2)
       (map (fn [[option value]]
              [(edn/read-string option) (edn/read-string value)]))
       (into {})))

(deftest command-uses-the-pinned-deps-new-exec-function
  (let [request (assoc (generator-request :lib "/tmp/a project")
                       :description "quotes \" stay data; $HOME stays data")
        command (generator/command request)]
    (is (= ["clojure" "-Sdeps"] (subvec command 0 2)))
    (is (= {:deps
            '{io.github.seancorfield/deps-new
              {:git/tag "v0.12.2"
               :git/sha "465b303"}}}
           (edn/read-string (nth command 2))))
    (is (= ["-X" "org.corfield.new/lib"] (subvec command 3 5)))
    (is (= {:name        "io.github.jus/generator-lib"
            :target-dir  "/tmp/a project"
            :developer   "jus tests"
            :description "quotes \" stay data; $HOME stays data"
            :license/id  "MIT"
            :build       :bb}
           (command-options command)))))

(deftest command-omits-a-blank-description
  (let [command (generator/command
                 (assoc (generator-request :app "/tmp/example")
                        :description "  "))]
    (is (not (contains? (command-options command) :description)))))

(deftest command-omits-an-absent-or-blank-developer
  (let [without-developer (generator/command
                           (dissoc (generator-request :app "/tmp/example")
                                   :developer))
        blank-developer (generator/command
                         (assoc (generator-request :app "/tmp/example")
                                :developer "  "))]
    (is (not (contains? (command-options without-developer) :developer)))
    (is (not (contains? (command-options blank-developer) :developer)))))

(deftest command-supports-project-rooted-source-options
  (let [command (generator/command
                 (assoc (generator-request :lib "/tmp/example")
                        :top "example"
                        :main "core"))]
    (is (= "example" (:top (command-options command))))
    (is (= "core" (:main (command-options command))))))

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
      (let [{:keys [result target bb-edn bb-content source]}
            (generated-project-result template)]
        (is (= 0 (:exit-code result)))
        (is (str/includes? (:out result) "Creating project from"))
        (is target)
        (is bb-edn)
        (is (str/includes? bb-content ":requires [[clojure.string :as str]]"))
        (is (not (str/includes? bb-content ":require [[")))
        (is source)))))

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
        (is (str/includes? (slurp source) "(ns example.core")))
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
      (let [process (generator/start! (generator-request :app target))]
        (generator/cancel! process)
        (is (not= 0 (:exit-code (generator/await! process))))
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
