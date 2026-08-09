(ns repl-handoff.launch-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [repl-handoff.launch :as launch]))

(deftest runtime-commands-use-readiness-aware-entry-points
  (let [working-directory "/tmp/repl-handoff-project"
        clojure-command (launch/runtime-command :clojure working-directory)
        rebel-command (launch/runtime-command :rebel working-directory)
        babashka-command (launch/runtime-command :babashka working-directory)
        cljs-command (launch/runtime-command :clojurescript working-directory)]
    (testing "Clojure delegates to its same-JVM bootstrap"
      (is (= ["clojure" "-M"] (subvec clojure-command 0 2)))
      (is (str/ends-with? (last clojure-command) "clojure_bootstrap.clj")))
    (testing "Rebel retains its pin, preload hook, and direct main entry point"
      (is (some #(str/includes? % "com.bhauman") rebel-command))
      (is (some #(str/ends-with? % "rebel_ready.clj") rebel-command))
      (is (some #{"rebel-readline.main"} rebel-command)))
    (testing "Babashka uses its built-in REPL after the readiness init"
      (is (= "bb" (first babashka-command)))
      (is (= "repl" (last babashka-command))))
    (testing "ClojureScript explicitly selects the Node REPL and managed cache"
      (is (= "--repl" (last cljs-command)))
      (is (some #{"node"} cljs-command))
      (is (some #(str/includes? % "jus/cljs-repl/1.12.145/")
                cljs-command)))))

(deftest command-construction-does-not-create-the-cljs-cache
  (let [cache-directory "/tmp/repl-handoff-pure-command-cache"]
    (with-redefs [launch/cljs-output-dir (constantly cache-directory)]
      (launch/runtime-command :clojurescript "/tmp/project")
      (is (not (.exists (java.io.File. cache-directory)))))))
