(ns jus.tui.installer-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jus.tui.installer :as installer])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-directory []
  (.toFile (Files/createTempDirectory "jus installer " (make-array FileAttribute 0))))

(defn- remove-directory! [dir]
  (doseq [file (reverse (file-seq dir))] (io/delete-file file)))

(defn- executable! [dir name script]
  (.mkdirs (io/file dir))
  (let [file (io/file dir name)]
    (spit file (str "#!/usr/bin/env bash\n" script "\n"))
    (.setExecutable file true)
    (.getAbsolutePath file)))

(deftest runtime-tool-mappings-and-private-prefix
  (with-redefs [installer/cache-directory (constantly "/private cache")
                installer/find-executable (constantly nil)]
    (doseq [[runtime tools] [[:clojure ["clojure" "babashka"]]
                            [:rebel ["clojure" "babashka"]]
                            [:babashka ["babashka"]]
                            [:clojurescript ["clojure" "node" "babashka"]]
                            [:jolt ["jolt" "babashka"]]
                            [:let-go ["let-go" "babashka"]]]]
      (let [request (installer/request runtime)]
        (is (= tools (:tools request)))
        (is (= "/private cache/local" (:prefix request)))
        (is (:bootstrap? request))
        (is (str/ends-with? (get-in request [:environment "PATH"])
                           ":/private cache/local/bin"))))))

(deftest partial-requirements-install-only-missing-tools
  (with-redefs [installer/find-executable
                (fn [name _] (when (not= name "node") (str "/existing/" name)))]
    (let [request (installer/request :clojurescript)]
      (is (= ["node"] (:tools request)))
      (is (= "/existing/in-1" (:installer request)))
      (is (false? (:bootstrap? request))))))

(deftest path-order-and-cache-reuse
  (let [dir (temp-directory)]
    (try
      (let [first-bin (str dir "/first")
            cached-bin (str dir "/local/bin")
            existing (executable! first-bin "jolt" "exit 0")
            cached (executable! cached-bin "jolt" "exit 0")]
        (is (= existing (installer/find-executable "jolt"
                                                   (str first-bin ":" cached-bin))))
        (is (= cached (installer/find-executable "jolt" cached-bin)))
        (spit (io/file first-bin "not-executable") "no")
        (is (nil? (installer/find-executable "not-executable" first-bin)))
        (with-redefs [installer/cache-directory (constantly (str dir))]
          (is (not (some #{"jolt"} (:missing (installer/request :jolt)))))))
      (finally (remove-directory! dir)))))

(deftest installation-is-isolated-and-verifies-results
  (let [captured (atom nil)
        request {:runtime :jolt :base "/private" :prefix "/private/local"
                 :tools ["jolt"] :installer "/existing/in-1"
                 :environment {"PATH" "/existing:/private/local/bin"
                               "IN1_UPDATE" "1" "IN1_ROOT" "/session"
                               "PREFIX" "/user"}}]
    (with-redefs-fn {#'installer/run-process!
                     (fn [environment command]
                       (reset! captured [environment command]) 0)
                     #'installer/find-executable (constantly "/installed/tool")}
      #(is (= (:environment request) (installer/install! request))))
    (let [[environment command] @captured]
      (is (= ["/existing/in-1" "--local" "PREFIX=/private/local" "jolt"] command))
      (is (= "/private/state" (get environment "IN1_ROOT")))
      (is (= "/private/downloads" (get environment "IN1_CACHE")))
      (is (not (contains? environment "PREFIX")))
      (is (not (contains? environment "IN1_UPDATE"))))
    (testing "installation failure is not mistaken for success"
      (with-redefs-fn {#'installer/run-process! (constantly 7)}
        #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"installation failed"
                              (installer/install! request)))))
    (testing "successful installer must actually provide the executables"
      (with-redefs-fn {#'installer/run-process! (constantly 0)
                       #'installer/find-executable (constantly nil)}
        #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"still missing"
                              (installer/install! request)))))))

(deftest real-installer-subprocess-populates-only-the-private-prefix
  (let [dir (temp-directory)
        prefix (str dir "/local")
        runtime (executable! dir "runtime" "printf '%s' \"$JUS_TEST_MARKER\"")
        command (executable! dir "in-1"
                  (str "set -eu\n"
                       "test \"$1\" = --local\n"
                       "test \"$3\" = jolt\n"
                       "test -z \"${IN1_UPDATE-}\"\n"
                       "prefix=${2#PREFIX=}\n"
                       "mkdir -p \"$prefix/bin\"\n"
                       "cp \"$FAKE_RUNTIME\" \"$prefix/bin/jolt\"\n"
                       "cp \"$FAKE_RUNTIME\" \"$prefix/bin/bb\""))
        environment (assoc (into {} (System/getenv))
                           "PATH" (str (System/getenv "PATH") ":" prefix "/bin")
                           "FAKE_RUNTIME" runtime "IN1_UPDATE" "1")
        request {:runtime :jolt :base (str dir) :prefix prefix :tools ["jolt"]
                 :installer command :environment environment}
        original-path (System/getenv "PATH")]
    (try
      (is (= environment (installer/install! request)))
      (is (.canExecute (io/file prefix "bin/jolt")))
      (is (= original-path (System/getenv "PATH")))
      (let [child (doto (ProcessBuilder. ^java.util.List [(str prefix "/bin/jolt")])
                    (.redirectErrorStream true))]
        (.put (.environment child) "JUS_TEST_MARKER" "private-child")
        (let [process (.start child)]
          (is (= "private-child" (slurp (.getInputStream process))))
          (is (zero? (.waitFor process)))))
      (finally (remove-directory! dir)))))

(deftest bootstrap-subprocess-handles-spaces-and-fetch-failure
  (let [dir (temp-directory)
        bin (str dir "/bin")
        prefix (str dir "/local")
        curl (executable! bin "curl"
               (str "printf '%s\\n' 'test \"$1\" = --local || return 8' "
                    "'test \"$2\" = in-1 || return 8' "
                    "'test \"$3\" = \"PREFIX=$EXPECTED_PREFIX\" || return 8' "
                    "'test \"$4\" = jolt || return 8'"))
        request {:runtime :jolt :base (str dir) :prefix prefix :tools ["jolt"]
                 :environment {"PATH" (str bin ":" (System/getenv "PATH"))
                               "EXPECTED_PREFIX" prefix}}]
    (try
      ;; Bootstrap is real; only the final executable verification is stubbed.
      (let [find-executable installer/find-executable]
        (with-redefs [installer/find-executable
                      (fn [name path]
                        (if (= name "bash") (find-executable name path)
                            "/fake/installed"))]
          (is (= (:environment request) (installer/install! request)))
          (spit curl "#!/usr/bin/env bash\nexit 22\n")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"installation failed"
                                (installer/install! request)))))
      (finally (remove-directory! dir)))))
