(require '[babashka.process :as process]
         '[clojure.string :as str])

(defn refresh-git-deps-docs!
  [& [release-tag]]
  (let [tag (or release-tag
                (-> (process/shell {:out :string} "git" "describe" "--tags" "--abbrev=0")
                    :out str/trim))
        sha (-> (process/shell {:out :string} "git" "rev-parse" "--verify"
                               (str "refs/tags/" tag "^{commit}"))
                :out str/trim)
        _ (when-not (re-matches #"[0-9a-f]{40,64}" sha)
            (throw (ex-info "Release tag did not resolve to a full commit SHA." {:tag tag})))
        template (slurp "docs/git-deps.template.md")]
    (spit "docs/git-deps.md"
          (str/replace template #"\{\{(TAG|SHA)\}\}"
                       (fn [[_ key]] (pr-str (if (= "TAG" key) tag sha)))))
    (println "Wrote docs/git-deps.md for" tag sha)))

(apply refresh-git-deps-docs! *command-line-args*)
