(ns jus.tui.repl-flow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [charm.message :as msg]
            [charm.program :as program]
            [charm.render.screen :as screen]
            [jus.tui.core :as core]
            [jus.tui.repls :as repls]
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
      (with-redefs [style/intel-mac? false]
        (test-fn)))))

(clojure.test/use-fixtures :each with-extended-platform)

(defn selected []
  (assoc (core/main-menu-state {})
         :step :repl-menu
         :menu-idx (.indexOf (mapv :id (repls/available-options)) :glojure)))

(defn missing-menu []
  (with-redefs [repls/discover (constantly nil)]
    (first (core/update-fn (selected) (msg/key-press :enter)))))

(defn clean-screen [text]
  (-> text
      core/strip-ansi
      (str/replace #"\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)" "")
      (str/replace #"[\p{Cntrl}&&[^\n\t]]" "")))

(deftest missing-dialect-offers-installation-and-restores-selection
  (let [state (missing-menu)]
    (is (= :repl-install-menu (:step state)))
    (is (= :glojure (:repl-id state)))
    (doseq [message [(msg/key-press :escape) (msg/key-press :enter)]]
      (let [[back command] (core/update-fn (assoc state :menu-idx 3) message)]
        (is (= :repl-menu (:step back)))
        (is (= (.indexOf (mapv :id (repls/available-options)) :glojure)
               (:menu-idx back)))
        (is (nil? command))))))

(deftest missing-runtime-without-in-1-support-skips-the-install-menu
  (let [jank-index (.indexOf (mapv :id (repls/available-options)) :jank)]
    (with-redefs [repls/discover (constantly nil)]
      (let [[state command]
            (core/update-fn (assoc (core/main-menu-state {})
                                   :step :repl-menu :menu-idx jank-index)
                            (msg/key-press :enter))]
        (is (= :repl-menu (:step state)))
        (is (str/includes? (:error state) "Required executable not found: jank"))
        (is (nil? command))))))

(deftest missing-runtime-menu-explains-the-temporary-copy-action
  (with-redefs [style/hyperlinks-enabled? (constantly true)]
    (let [screen (core/view (assoc (missing-menu) :term-width 80 :term-height 24))
          plain (clean-screen screen)
          first-line "This will copy an install snippet to your clipboard."]
      (is (str/includes? plain
                         (str style/logo
                              " jus ╱ Launch Interactive REPL ╱ Glojure")))
      (is (str/includes? plain
                         (str "  " first-line "\n"
                              "  \n"
                              "  This will be a temp install using in-1, a tool for installing\n"
                              "  things quickly and easily, with no prerequisites.")))
      (is (str/includes? plain
                         (str "> Glojure temporary install & launch  "
                              "Copy in-1 command to clipboard")))
      (is (str/includes? plain
                         (str "Glojure local install & launch      "
                              "Copy in-1 command to clipboard")))
      (is (not (str/includes? plain "Instant Dialect Commands")))
      (is (not (str/includes? screen (style/secondary first-line))))
      (is (str/includes? plain
                         "Enter: copy,  ↑↓: menus,  Esc: back,  Ctrl-C: quit")))))

(deftest local-copy-action-has-local-install-helper
  (let [screen-for (fn [index]
                     (clean-screen
                      (core/view (assoc (missing-menu)
                                        :term-width 80 :term-height 24 :menu-idx index))))]
    (is (str/includes?
         (screen-for 1)
         (str "  This will copy an install snippet to your clipboard.\n"
              "  \n"
              "  This will be a local install using in-1, a tool for installing\n"
              "  things quickly and easily, with no prerequisites.")))
    (is (not (str/includes? (screen-for 1) "bash -c")))
    (is (str/includes? (screen-for 2)
                       "  https://github.com/glojurelang/glojure#prerequisites"))))

(deftest unsupported-intel-mac-installations-offer-only-the-guide-and-cancel
  (with-redefs [style/intel-mac? true]
    (let [state (assoc (missing-menu)
                       :repl-id :janet :term-width 100 :term-height 24 :menu-idx 0)
          screen (clean-screen (core/view state))]
      (is (str/includes? screen (str style/error-prefix "Janet installation not found.")))
      (is (str/includes? screen (str style/error-prefix
                                     "Quick install option via in-1 not available for Intel Mac")))
      (is (str/includes? screen "View Janet Install Guide"))
      (is (str/includes? screen "Cancel"))
      (is (not (str/includes? screen "Install Janet, Temporary")))
      (is (not (str/includes? screen "Janet local install & launch")))
      (is (= 1 (:menu-idx (first (core/update-fn (assoc state :menu-idx 1)
                                                 (msg/key-press :down)))))))))

(deftest discovered-runtime-launches-the-resolved-path
  (with-redefs [repls/discover (constantly "/some path/bin/glj")]
    (let [[state command] (core/update-fn (selected) (msg/key-press :enter))]
      (is (= :repl (:action state)))
      (is (= "/some path/bin/glj" (:repl-executable state)))
      (is (= program/quit-cmd command)))))

(deftest copy-actions-write-the-clipboard-and-stay-on-the-menu
  (doseq [[index mode snippet]
          [[0 :temporary "in-1 --temp glj && glj"]
           [1 :local "in-1 --local glj && glj"]]]
    (let [[copying command] (core/update-fn (assoc (missing-menu) :menu-idx index)
                                            (msg/key-press :enter))
          completion (atom nil)
          output (with-out-str (reset! completion ((:fn command))))
          [state next-command] (core/update-fn copying @completion)]
      (is (= (screen/copy-to-clipboard snippet) output))
      (is (= :repl-install-menu (:step state)))
      (is (= index (:menu-idx state)))
      (is (= mode (:repl-install-copy-mode state)))
      (is (nil? next-command))))
  (let [[copying command] (core/update-fn (missing-menu) (msg/key-press :enter))
        completion (atom nil)
        _ (with-out-str (reset! completion ((:fn command))))
        state (first (core/update-fn copying @completion))
        snippet "in-1 --temp glj && glj"
        screen (core/view (assoc state :term-width 80 :term-height 24))
        plain (clean-screen screen)
        confirmation "✓ Copied to clipboard: Glojure in-1 temp install command"]
    (is (str/includes? plain
                       (str "  " confirmation "\n"
                            "  \n"
                            "  Open a fresh terminal tab and paste.\n"
                            "  \n"
                            "  If clipboard access is blocked, copy this manually:\n"
                            "  " snippet "\n"
                            "  \n"
                            "  Install in-1 for Bash/Zsh:\n"
                            "  source <(curl -sL in-1.cc) in-1\n"
                            "  \n"
                            "  Install in-1 for Fish:\n"
                            "  curl -sL in-1.cc | source - in-1")))
    (is (str/includes? screen (style/primary "✓ Copied to clipboard")))
    (is (not (str/includes? plain "Glojure installation not found.")))
    (is (not (str/includes? plain "Glojure temporary install & launch")))
    (let [next-state (first (core/update-fn state (msg/key-press :down)))]
      (is (nil? (:repl-install-copy-mode next-state)))
      (is (str/includes? (clean-screen (core/view (assoc next-state
                                                         :term-width 80 :term-height 24)))
                         "This will be a local install using in-1")))))

(deftest links-open-and-failures-have-recovery
  (let [state (assoc (missing-menu) :menu-idx 2)
        opened (atom nil)]
    (with-redefs [core/open-url! #(do (reset! opened %) true)]
      (is (= state (first (core/update-fn state (msg/key-press :enter)))))
      (is (= (:guide (repls/option :glojure)) @opened)))
    (with-redefs [core/open-url! (constantly false)]
      (let [failed (first (core/update-fn state (msg/key-press :enter)))]
        (is (= :repl-install-menu (:step failed)))
        (is (str/includes? (clean-screen (core/view failed)) "Unable to open"))))))

(deftest rendering-stays-within-the-viewport
  (with-redefs [style/hyperlinks-enabled? (constantly true)]
    (doseq [width [32 80 120] height [16 24] index (range 4)]
      (let [state (assoc (missing-menu) :term-width width :term-height height :menu-idx index)
            screen (core/view state)
            plain (clean-screen screen)]
        (is (every? #(<= (count %) width) (str/split-lines plain)) (str width " " index))
        (is (<= (count (str/split-lines plain)) height))
        (when (= index 2)
          (is (str/includes? plain core/open-in-browser-icon))
          (is (str/includes? screen "\u001b]8;;https://github.com/glojurelang/glojure#prerequisites"))))))
  (let [state (assoc (selected) :menu-idx 11 :term-height 16)]
    (is (not (str/includes? (core/view state) "Phel")))))

(deftest helper-links-have-readable-fallback
  (with-redefs [style/hyperlinks-enabled? (constantly true)]
    (is (str/includes? (str/join (style/helper-lines "Use [in-1](https://in-1.cc) here" 20))
                       "\u001b]8;;https://in-1.cc")))
  (with-redefs [style/hyperlinks-enabled? (constantly false)]
    (is (= ["Use in-1 here"] (style/helper-lines "Use [in-1](https://in-1.cc) here" 20)))
    (let [url (:guide (repls/option :glojure))
          guide (core/view (assoc (missing-menu) :term-width 120 :term-height 24 :menu-idx 2))]
      (is (str/includes? guide (style/secondary url))))))

(deftest copy-menu-fits-small-terminals
  (doseq [width [20 32] height [12 16]]
    (let [state (assoc (missing-menu) :term-width width :term-height height)
          lines (str/split-lines (clean-screen (core/view state)))]
      (is (every? #(<= (count %) width) lines) (str width))
      (is (<= (count lines) height) (str width "x" height))))
  (doseq [[width height] [[20 12] [32 16]]]
    (let [state (assoc (missing-menu)
                       :repl-install-copy-mode :temporary
                       :term-width width :term-height height)
          rendered (clean-screen (core/view state))
          lines (str/split-lines rendered)]
      (is (every? #(<= (count %) width) lines) (str "copied " width))
      (is (<= (count lines) height) (str "copied " width "x" height))
      (if (= width 32)
        (is (str/includes? rendered "✓ Copied to clipboard:"))
        (is (str/includes? rendered "✓ Copied to") "compact confirmation")))))
