(ns jus.tui.repl-installer-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [jus.tui.repls :as repls]
            [jus.tui.repl-installer :as installer])
  (:import (java.lang ProcessHandle)))

(defn executable! [path text]
  (spit (str path) text)
  (.setExecutable (io/file (str path)) true)
  (str path))

(defn fixture! []
  (let [root (str (fs/create-temp-dir {:prefix "jus installer test "}))
        bin (str (fs/create-dirs (fs/path root "tools")))
        home (str (fs/create-dirs (fs/path root "home")))
        tmp (str (fs/create-dirs (fs/path root "tmp")))
        env {:home home
             :tmp tmp
             :path (str bin java.io.File/pathSeparator (System/getenv "PATH"))}]
    (executable! (fs/path bin "curl")
                 "#!/bin/bash\nwhile [ $# -gt 0 ]; do if [ \"$1\" = --output ]; then shift; destination=$1; fi; shift; done\ncp \"$HOME/bootstrap\" \"$destination\"\n")
    (executable! (fs/path bin "make") "#!/bin/bash\necho 'GNU Make 4.4'\n")
    (executable! (fs/path bin "git") "#!/bin/bash\nexit 0\n")
    {:root root :bin bin :env env}))

(defmacro with-fixture [[binding] & body]
  `(let [~binding (fixture!)]
     (try ~@body (finally (fs/delete-tree (:root ~binding))))))

(defmacro deftest-installation [name & body]
  `(deftest ~name
     (if (repls/installation-supported?)
       (do ~@body)
       (is true "in-1 installation is deferred on this platform"))))

(defn install-result [env mode bootstrap]
  (spit (io/file (:home env) "bootstrap") bootstrap)
  (let [handle (installer/start! {:runtime :glojure :mode mode :locations env})
        result (deref (:completion handle) 10000 ::timeout)]
    (when (= result ::timeout) (installer/cancel! handle))
    result))

(def successful-bootstrap
  "if [ \"$1\" = --temp ]; then prefix=$TMPDIR/in-1; else prefix=${3#PREFIX=}; fi\nmkdir -p \"$prefix/bin\"\nprintf '#!/bin/bash\\necho ready\\n' > \"$prefix/bin/glj\"\nchmod +x \"$prefix/bin/glj\"\n")

(deftest-installation installs-to-the-selected-destination
  (with-fixture [fixture]
    (doseq [mode [:temporary :persistent]]
      (let [env (:env fixture)
            result (install-result env mode successful-bootstrap)]
        (is (= :installed (:status result)) (pr-str result))
        (is (= (str (io/file (repls/install-prefix mode env) "bin/glj"))
               (:executable result)))
        (is (.canExecute (io/file (:executable result))))))))

(deftest-installation failures-have-diagnostics-and-never-report-success
  (with-fixture [fixture]
    (let [env (:env fixture)]
      (testing "installer failure"
        (let [result (install-result env :temporary "echo 'download exploded' >&2; return 17\n")]
          (is (= :failed (:status result)))
          (is (= 17 (:exit-code result)))
          (is (str/includes? (:diagnostics result) "download exploded"))))
      (testing "missing executable despite zero exit"
        (is (str/includes? (:error (install-result env :temporary "return 0\n"))
                           "without an executable")))
      (testing "failed bootstrap download is not sourced"
        (executable! (fs/path (:bin fixture) "curl") "#!/bin/bash\necho offline >&2\nexit 22\n")
        (let [result (install-result env :persistent successful-bootstrap)]
          (is (= :failed (:status result)))
          (is (= 22 (:exit-code result)))
          (is (not (fs/exists? (fs/path (:home env) ".local/bin/glj")))))))))

(deftest-installation cancellation-stops-installer-and-descendants
  (with-fixture [fixture]
    (let [env (:env fixture)
          pid-file (io/file (:tmp env) "child.pid")
          _ (spit (io/file (:home env) "bootstrap")
                  "sleep 120 &\necho $! > \"$TMPDIR/child.pid\"\nwait\n")
          handle (installer/start! {:runtime :glojure :mode :temporary :locations env})]
      (try
        (loop [remaining 100]
          (when (and (pos? remaining) (not (.exists pid-file)))
            (Thread/sleep 30) (recur (dec remaining))))
        (is (.exists pid-file))
        (installer/cancel! handle)
        (let [result (deref (:completion handle) 5000 ::timeout)]
          (is (= :cancelled (:status result)) (pr-str result)))
        (when (.exists pid-file)
          (let [pid (Long/parseLong (str/trim (slurp pid-file)))
                child (ProcessHandle/of pid)]
            (is (or (not (.isPresent child)) (not (.isAlive (.get child)))))))
        (finally (installer/cancel! handle))))))

(deftest discovery-prefers-path-then-persistent-then-temporary
  (with-fixture [fixture]
    (let [env (:env fixture)
          temporary (io/file (repls/install-prefix :temporary env) "bin/glj")
          persistent (io/file (repls/install-prefix :persistent env) "bin/glj")
          on-path (io/file (:bin fixture) "glj")]
      (is (nil? (repls/discover :glojure env)))
      (doseq [file [temporary persistent on-path]]
        (io/make-parents file)
        (executable! file "#!/bin/bash\nexit 0\n")
        (is (= (str file) (repls/discover :glojure env))))
      (.setExecutable on-path false)
      (is (= (str persistent) (repls/discover :glojure env))))))

(deftest-installation output-is-bounded-and-terminal-controls-are-removed
  (with-fixture [fixture]
    (let [result (install-result (:env fixture) :temporary
                                 "printf '\\033[31m'; i=0; while [ $i -lt 14000 ]; do printf x; i=$((i+1)); done; return 1\n")]
      (is (<= (count (:diagnostics result)) installer/diagnostic-limit))
      (is (not (str/includes? (:diagnostics result) "\u001b"))))))

(deftest-installation cancellation-after-parent-exits-does-not-wait-for-inherited-output
  (with-fixture [fixture]
    (let [env (:env fixture)
          _ (spit (io/file (:home env) "bootstrap")
                  "sleep 120 &\necho $! > \"$TMPDIR/child.pid\"\nsleep 0.3\nreturn 0\n")
          handle (installer/start! {:runtime :glojure :mode :temporary :locations env})
          pid-file (io/file (:tmp env) "child.pid")]
      (try
        (loop [remaining 100]
          (when (and (pos? remaining) (not (.exists pid-file)))
            (Thread/sleep 30) (recur (dec remaining))))
        (Thread/sleep 500)
        (installer/cancel! handle)
        (is (not= ::timeout (deref (:completion handle) 3000 ::timeout)))
        (when (.exists pid-file)
          (let [child (ProcessHandle/of (Long/parseLong (str/trim (slurp pid-file))))]
            (is (or (not (.isPresent child)) (not (.isAlive (.get child)))))))
        (finally (installer/cancel! handle))))))

(deftest-installation missing-prerequisites-produce-actionable-errors
  (with-fixture [fixture]
    (let [env (assoc (:env fixture) :path (:bin fixture))
          result (install-result env :temporary successful-bootstrap)]
      (is (= :failed (:status result)))
      (is (str/includes? (:error result) "Required executable not found: bash")))))
