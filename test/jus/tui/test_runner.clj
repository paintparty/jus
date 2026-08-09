(ns jus.tui.test-runner
  (:require [clojure.test :as test]))

(def suites
  {:unit        ['charm.render.core-test
                 'jus.tui.core-test
                 'jus.tui.repls-test
                 'jus.tui.tasks-test
                 'repl-handoff.launch-test]
   :integration ['jus.tui.config-test
                 'jus.tui.generator-test]})

(defn- namespaces-for
  [suite]
  (case suite
    :all (vec (distinct (concat (:unit suites) (:integration suites))))
    (get suites suite)))

(defn- usage []
  (str "Usage: clojure -M:test -m jus.tui.test-runner [unit|integration|all]\n"))

(defn -main
  [& args]
  (let [suite (keyword (or (first args) "all"))]
    (if-let [namespaces (namespaces-for suite)]
      (do
        (apply require namespaces)
        (let [{:keys [fail error]} (apply test/run-tests namespaces)]
          (System/exit (if (zero? (+ fail error)) 0 1))))
      (do
        (binding [*out* *err*]
          (print (usage)))
        (System/exit 2)))))
