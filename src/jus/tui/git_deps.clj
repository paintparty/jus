(ns jus.tui.git-deps
  "Scaffold a library's git dependency guide and local refresh task."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(defn scaffold!
  [target data]
  (let [template (-> (slurp (io/resource "jus/tui/templates/git-deps.template.md"))
                     (str/replace #"\{\{(PROJECT|PROJECT_ARTIFACT|PROJECT_GIT_URL)\}\}"
                                  (fn [[_ key]] (get data key))))
        docs (io/file target "docs")
        scripts (io/file target "scripts")
        bb-edn (io/file target "bb.edn")]
    (.mkdirs docs)
    (.mkdirs scripts)
    (spit (io/file docs "git-deps.template.md") template)
    (spit (io/file docs "git-deps.md")
          (str "> No release has been tagged yet. The tag and SHA below are placeholders.\n"
               "> After tagging your first release, run `bb git-deps-docs`.\n\n"
               (str/replace template #"\{\{(TAG|SHA)\}\}"
                            (fn [[_ key]] (pr-str (if (= "TAG" key) "RELEASE_TAG" "COMMIT_SHA"))))))
    (spit (io/file scripts "git_deps_docs.clj")
          (slurp (io/resource "jus/tui/templates/git-deps-docs.clj")))
    (spit bb-edn
          (-> (z/of-string (slurp bb-edn))
              z/down
              (z/find-value z/right :tasks)
              z/right
              (z/assoc 'git-deps-docs
                       {:doc "Refresh docs/git-deps.md from a release tag (defaults to latest reachable tag)."
                        :task '(load-file "scripts/git_deps_docs.clj")})
              z/root-string))))
