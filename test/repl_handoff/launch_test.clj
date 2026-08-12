(ns repl-handoff.launch-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [repl-handoff.launch :as launch])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def logo-fixtures
  [["Windows 11" "*"]
   ["windows 10" "*"]
   ["Linux" "◒" #_"☯"]
   ["Mac OS X" "◒" #_"☯"]])

(deftest logo-follows-the-operating-system
  (doseq [[os-name expected] logo-fixtures]
    (is (= expected (launch/logo os-name)))))

(deftest spinner-renders-the-logo-from-its-environment
  (doseq [logo (distinct (map second logo-fixtures))]
    (let [state-dir (.toFile
                     (Files/createTempDirectory
                      "jus-spinner-test-"
                      (make-array FileAttribute 0)))
          builder (ProcessBuilder.
                   ^java.util.List
                   ["sh" "scripts/repl_handoff/spinner.sh" "run"
                    (.getCanonicalPath state-dir) "Test"
                    "1"])
          _ (.put (.environment builder) "JUS_SPINNER_LOGO" logo)
          _ (.put (.environment builder)
                  "PATH"
                  (str "test/repl_handoff/fixtures:"
                       (System/getenv "PATH")))
          process (-> builder (.redirectErrorStream true) (.start))
          output (future (slurp (.getInputStream process)))]
      (try
        (Thread/sleep 650)
        (.destroy process)
        (let [rendered @output]
          (.waitFor process)
          (is (str/includes? rendered (str logo "  Starting Test..."))))
        (finally
          (.destroyForcibly process)
          (doseq [file (or (.listFiles state-dir) [])]
            (.delete file))
          (.delete state-dir))))))
(deftest runtime-commands-use-readiness-aware-entry-points
  (let [working-directory "/tmp/repl-handoff-project"
        clojure-command (launch/runtime-command :clojure working-directory)
        rebel-command (launch/runtime-command :rebel working-directory)
        babashka-command (launch/runtime-command :babashka working-directory)
        cljs-command (launch/runtime-command :clojurescript working-directory)
        jolt-command (launch/runtime-command :jolt working-directory)
        let-go-command (launch/runtime-command :let-go working-directory)]
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
                cljs-command)))
    (testing "Jolt runs its readiness bootstrap in the selected project"
      (is (= ["jolt" "run"] (subvec jolt-command 0 2)))
      (is (str/ends-with? (last jolt-command) "jolt_ready.clj")))
    (testing "Let-go runs its readiness bootstrap before entering the REPL"
      (is (= ["lg" "-r"] (subvec let-go-command 0 2)))
      (is (str/ends-with? (last let-go-command) "let_go_ready.clj")))))

(deftest command-construction-does-not-create-the-cljs-cache
  (let [cache-directory "/tmp/repl-handoff-pure-command-cache"]
    (with-redefs [launch/cljs-output-dir (constantly cache-directory)]
      (launch/runtime-command :clojurescript "/tmp/project")
      (is (not (.exists (java.io.File. cache-directory)))))))
