(ns jus.tui.repl-flow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [charm.message :as msg]
            [charm.program :as program]
            [jus.tui.core :as core]
            [jus.tui.repls :as repls]
            [jus.tui.repl-installer :as installer]
            [jus.tui.style :as style]))

(defn with-extended-platform [test-fn]
  (let [available-options repls/available-options
        installation-supported? repls/installation-supported?]
    (with-redefs [repls/available-options
                  (fn
                    ([] (available-options "Linux"))
                    ([os-name] (available-options os-name)))
                  repls/installation-supported?
                  (fn
                    ([] true)
                    ([os-name] (installation-supported? os-name)))]
      (test-fn))))

(clojure.test/use-fixtures :each with-extended-platform)

(defn selected []
  (assoc (core/main-menu-state {}) :step :repl-menu :menu-idx 6))

(defn missing-menu []
  (with-redefs [repls/discover (constantly nil)]
    (first (core/update-fn (selected) (msg/key-press :enter)))))

(deftest missing-dialect-offers-installation-and-restores-selection
  (let [state (missing-menu)]
    (is (= :repl-install-menu (:step state)))
    (is (= :glojure (:repl-id state)))
    (doseq [message [(msg/key-press :escape) (msg/key-press :enter)]]
      (let [[back command] (core/update-fn (assoc state :menu-idx 3) message)]
        (is (= :repl-menu (:step back)))
        (is (= 6 (:menu-idx back)))
        (is (nil? command))))))

(deftest installation-menu-uses-the-shared-app-shell
  (with-redefs [style/hyperlinks-enabled? (constantly true)]
    (let [screen (core/view (assoc (missing-menu) :term-width 80 :term-height 24))
          plain (installer/clean-diagnostics screen)]
      (is (str/includes? plain
                         (str style/logo
                              " jus ╱ Launch Interactive REPL ╱ Glojure")))
      (is (str/includes? plain
                         (str "  This will run:\n"
                              "  source <(curl -fsSL https://in-1.cc) --temp glj && glj\n"
                              "  \n"
                              "  This is a temp install using in-1, a tool for\n"
                              "  installing things quickly and easily, with no prerequisites.")))
      (is (str/includes? plain
                         "Install Glojure, Temporary   Installs via in-1 for current session"))
      (is (str/includes? plain
                         "Install Glojure, Persistent  Installs via in-1"))
      (is (not (str/includes? plain "Instant Dialect Commands")))
      (is (str/includes? screen
                         "\u001b]8;;https://in-1.cc\u001b\\in-1\u001b]8;;\u001b\\"))
      (is (str/includes? plain
                         "Enter: next,  ↑↓: menus,  Esc: back,  Ctrl-C: quit")))))

(deftest installation-helper-copy-is-preserved
  (let [screen-for (fn [index]
                     (installer/clean-diagnostics
                      (core/view (assoc (missing-menu)
                                        :term-width 80 :term-height 24 :menu-idx index))))]
    (is (str/includes?
         (screen-for 1)
         (str "  This will run:\n"
              "  source <(curl -fsSL https://in-1.cc) --local glj PREFIX=\"$HOME/.local\" &&\n"
              "  glj\n"
              "  \n"
              "  This is a local install using in-1, a tool for\n"
              "  installing things quickly and easily, with no prerequisites.\n"
              "  It will install Glojure in $HOME/.local/bin/glj")))
    (is (str/includes? (screen-for 2)
                       "  https://github.com/glojurelang/glojure#installation"))))

(deftest unsupported-intel-mac-installations-offer-only-the-guide-and-cancel
  (with-redefs [style/intel-mac? true]
    (let [state (assoc (missing-menu)
                       :repl-id :janet :term-width 100 :term-height 24 :menu-idx 0)
          screen (installer/clean-diagnostics (core/view state))]
      (is (str/includes? screen "Quick install option via in-1 not available for Intel Mac"))
      (is (str/includes? screen "View Janet Install Guide"))
      (is (str/includes? screen "Cancel"))
      (is (not (str/includes? screen "Install Janet, Temporary")))
      (is (not (str/includes? screen "Install Janet, Persistent")))
      (is (= 1 (:menu-idx (first (core/update-fn (assoc state :menu-idx 1)
                                                 (msg/key-press :down)))))))))

(deftest discovered-runtime-launches-the-resolved-path
  (with-redefs [repls/discover (constantly "/some path/bin/glj")]
    (let [[state command] (core/update-fn (selected) (msg/key-press :enter))]
      (is (= :repl (:action state)))
      (is (= "/some path/bin/glj" (:repl-executable state)))
      (is (= program/quit-cmd command)))))

(deftest completion-and-cancellation-have-distinct-outcomes
  (with-redefs [installer/start! (constantly :fake-handle)
                installer/cancel! (constantly nil)]
    (let [[state _] (core/update-fn (missing-menu) (msg/key-press :enter))
          operation (get-in state [:repl-install :operation])
          complete {:type :repl-install-complete :operation operation
                    :result {:status :installed :executable "/tmp/in-1/bin/glj"}}]
      (is (= :repl-installing (:step state)))
      (is (= state (first (core/update-fn state (assoc complete :operation "stale"))))))
    (let [[state _] (core/update-fn (missing-menu) (msg/key-press :enter))
          operation (get-in state [:repl-install :operation])
          complete {:type :repl-install-complete :operation operation
                    :result {:status :installed :executable "/tmp/in-1/bin/glj"}}]
      (testing "success launches automatically"
        (let [[launched command] (core/update-fn state complete)]
          (is (= :repl (:action launched)))
          (is (= program/quit-cmd command))))
      (testing "Escape waits for cleanup, then returns even if success raced cancellation"
        (let [[cancelling _] (core/update-fn state (msg/key-press :escape))
              [back command] (core/update-fn cancelling complete)]
          (is (= :repl-installing (:step cancelling)))
          (is (= :repl-menu (:step back)))
          (is (= 6 (:menu-idx back)))
          (is (nil? command))))
      (testing "Ctrl-C waits for cleanup, then exits"
        (let [[cancelling _] (core/update-fn state (msg/key-press "c" :ctrl true))
              [back command] (core/update-fn cancelling complete)]
          (is (= 130 (:exit-code back)))
          (is (= program/quit-cmd command))))
      (testing "failure shows recovery menu"
        (let [[failed _] (core/update-fn state (assoc complete :result {:status :failed :error "offline"}))]
          (is (= :repl-error (:step failed)))
          (is (str/includes? (core/view failed) "offline"))
          (is (= :repl-menu (:step (first (core/update-fn failed (msg/key-press :enter)))))))))))

(deftest links-open-and-failures-have-recovery
  (let [state (assoc (missing-menu) :menu-idx 2)
        opened (atom nil)]
    (with-redefs [core/open-url! #(do (reset! opened %) true)]
      (is (= state (first (core/update-fn state (msg/key-press :enter)))))
      (is (= (:guide (repls/option :glojure)) @opened)))
    (with-redefs [core/open-url! (constantly false)]
      (is (= :repl-error (:step (first (core/update-fn state (msg/key-press :enter)))))))))

(deftest rendering-stays-within-the-viewport
  (with-redefs [style/hyperlinks-enabled? (constantly true)]
    (doseq [width [32 80 120] height [16 24] index (range 4)]
      (let [state (assoc (missing-menu) :term-width width :term-height height :menu-idx index)
            screen (core/view state)
            plain (installer/clean-diagnostics screen)]
        (is (every? #(<= (count %) width) (str/split-lines plain)) (str width " " index))
        (is (<= (count (str/split-lines plain)) height))
        (when (= index 2)
          (is (str/includes? plain core/open-in-browser-icon))
          (is (str/includes? screen "\u001b]8;;https://github.com/glojurelang/glojure#installation"))))))
  (let [state (assoc (selected) :menu-idx 12 :term-height 16)]
    (is (str/includes? (core/view state) "Phel"))))

(deftest helper-links-have-readable-fallback
  (with-redefs [style/hyperlinks-enabled? (constantly true)]
    (is (str/includes? (str/join (style/helper-lines "Use [in-1](https://in-1.cc) here" 20))
                       "\u001b]8;;https://in-1.cc")))
  (with-redefs [style/hyperlinks-enabled? (constantly false)]
    (is (= ["Use in-1 here"] (style/helper-lines "Use [in-1](https://in-1.cc) here" 20)))))

(deftest progress-and-errors-fit-small-terminals
  (doseq [step [:repl-installing :repl-error :repl-install-menu]
          width [20 32] height [12 16]]
    (let [state (assoc (missing-menu) :step step :term-width width :term-height height
                       :repl-install {:frame 0} :error (apply str (repeat 40 "error details\n")))
          lines (str/split-lines (installer/clean-diagnostics (core/view state)))]
      (is (every? #(<= (count %) width) lines) (str step " " width))
      (is (<= (count lines) height) (str step " " width "x" height)))))

(deftest installation-spinner-keeps-its-message-column-stable
  (let [render-frame (fn [frame]
                       (-> (missing-menu)
                           (assoc :step :repl-installing
                                  :term-width 80 :term-height 24
                                  :repl-install {:frame frame})
                           core/view
                           core/strip-ansi))
        visible-frame (render-frame 0)
        blank-frame (render-frame 2)]
    (is (= (.indexOf visible-frame "Installing Glojure…")
           (.indexOf blank-frame "Installing Glojure…")))
    (is (str/includes? visible-frame
                       "Enter: next,  ↑↓: menus,  Esc: back,  Ctrl-C: quit"))
    (is (not (str/includes? visible-frame "Escape cancels · Ctrl-C exits")))))
