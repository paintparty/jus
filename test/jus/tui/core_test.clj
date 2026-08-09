(ns jus.tui.core-test
  (:require [charm.components.text-input :as text-input]
            [charm.message :as msg]
            [charm.program :as program]
            [jus.tui.config :as config]
            [jus.tui.core :as core]
            [jus.tui.animation :as animation]
            [jus.tui.data :as data]
            [jus.tui.generator :as generator]
            [jus.tui.style :as style]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(def ^:private example-global-config
  (config/project-config
   {:groups ['io.github.example]
    :developers ["Jane Developer"]
    :parent-dirs ["/tmp/projects"]}))

(defn- rendered-glyph-cells
  [rendered]
  (vec
   (for [[row line] (map-indexed vector
                                 (str/split (core/strip-ansi rendered) #"\n" -1))
         [column ch] (map-indexed vector line)
         :when (not= \space ch)]
     [row column ch])))

(deftest project-wizard-constructor-and-init-use-the-config-boundary
  (let [global-config example-global-config
        project-state (core/project-wizard-state global-config)]
    (is (= :project-template (:step project-state)))
    (is (= global-config (:global-config project-state)))
    (is (= 80 (:term-width project-state)))
    (with-redefs [animation/opening-animation? false
                  config/global-config-path (constantly "/tmp/config.edn")
                  config/config-exists? (constantly true)
                  config/load-config (constantly global-config)]
      (let [[initialized command] (core/init)]
        (is (= :main-menu (:step initialized)))
        (is (= global-config (:global-config initialized)))
        (is (= 80 (:term-width initialized)))
        (is (nil? command))))
    (with-redefs [animation/opening-animation? false
                  config/global-config-path (constantly "/tmp/config.edn")
                  config/config-exists? (constantly false)]
      (let [[initialized command] (core/init)]
        (is (= :main-menu (:step initialized)))
        (is (= {} (:global-config initialized)))
        (is (nil? command))))))

(deftest missing-config-opens-the-project-wizard-with-empty-preferences
  (with-redefs [animation/opening-animation? false
                core/dev-success-sequence? false
                core/dev-opening-sequence? false
                config/global-config-path (constantly "/tmp/config.edn")
                config/config-exists? (constantly false)]
    (let [initial (first (core/init))
          [project _] (core/update-fn initial (msg/key-press :enter))]
      (is (= :main-menu (:step initial)))
      (is (= {} (:global-config initial)))
      (is (= :project-template (:step project)))
      (is (= {} (:global-config project))))))

(deftest opening-animation-converges-then-reveals-the-header-and-main-menu
  (with-redefs [animation/opening-animation? true
                animation/confetti-animation :polar
                config/global-config-path (constantly "/tmp/config.edn")
                config/config-exists? (constantly false)]
    (let [[opening command] (core/init)
          finished-confetti (assoc-in opening
                                      [:opening-animation :confetti]
                                      {:animation :polar
                                       :direction :in
                                       :frame 0
                                       :center {:row 0 :column 0}
                                       :tracks []})
          [header-0 header-command]
          (core/update-fn finished-confetti (msg/key-press "confetti-tick"))
          [header-1 _] (core/update-fn header-0
                                       (msg/key-press "opening-header-tick"))
          [header-2 _] (core/update-fn header-1
                                       (msg/key-press "opening-header-tick"))
          [header-3 _] (core/update-fn header-2
                                       (msg/key-press "opening-header-tick"))
          [header-4 _] (core/update-fn header-3
                                       (msg/key-press "opening-header-tick"))
          [header-5 _] (core/update-fn header-4
                                       (msg/key-press "opening-header-tick"))
          [header-6 _] (core/update-fn header-5
                                       (msg/key-press "opening-header-tick"))
          [menu menu-command] (core/update-fn
                               header-6
                               (msg/key-press "opening-header-tick"))
          rendered-headers (mapv core/view
                                 [header-0 header-1 header-2 header-3 header-4 header-5 header-6])
          visible-headers (mapv (comp core/strip-ansi core/view)
                                [header-0 header-1 header-2 header-3 header-4 header-5 header-6])
          position-of (fn [rendered glyph]
                        (some (fn [[row column ch]]
                                (when (= glyph ch)
                                  [row column]))
                              (rendered-glyph-cells rendered)))
          final-confetti (assoc (get-in opening
                                        [:opening-animation :confetti])
                                :frame (dec animation/reverse-confetti-total-frames))]
      (is (true? animation/opening-animation?))
      (is (= 42 animation/reverse-confetti-total-frames))
      (is (= 8 animation/reverse-confetti-frame-rate))
      (is (= 250 animation/post-confetti-blank-screen-pause))
      (is (= ["\n  ☯" "\n  ☯" "\n  ☯ j" "\n  ☯ ju" "\n  ☯ jus" "\n  ☯ jus" "\n  ☯ jus"]
             visible-headers))
      (is (int? animation/opening-header-animation-frame-rate))
      (is (= :in (get-in opening [:opening-animation :confetti :direction])))
      (is (= :confetti (get-in opening [:opening-animation :phase])))
      (is (= :cmd (:type command)))
      (is (= :cmd (:type header-command)))
      (is (= animation/opening-header-animation-frames rendered-headers))
      (is (str/includes? (nth rendered-headers 4) (style/primary-italic "jus")))
      (is (str/includes? (nth rendered-headers 5) (style/accent-italic "jus")))
      (is (str/includes? (nth rendered-headers 6) (style/accent-italic "jus")))
      (is (= [(:row core/main-menu-logo-position)
              (:column core/main-menu-logo-position)]
             (position-of (animation/render-confetti final-confetti 80 24) \☯)
             (position-of (last rendered-headers) \☯)
             (position-of (core/view menu) \☯)))
      (is (nil? (:opening-animation menu)))
      (is (str/includes? (core/strip-ansi (core/view menu)) "Main menu"))
      (is (nil? menu-command)))))

(deftest opening-animation-starts-with-reverse-confetti-not-a-standalone-logo
  (with-redefs [animation/opening-animation? true
                animation/confetti-animation :polar
                config/global-config-path (constantly "/tmp/config.edn")
                config/config-exists? (constantly false)]
    (let [[opening _] (core/init)
          initial-view (core/strip-ansi (core/view opening))]
      (is (= 0 (get-in opening [:opening-animation :confetti :frame])))
      (is (not (str/includes? initial-view "☯")))
      (is (str/includes? initial-view "●")))))

(deftest project-name-copy-follows-the-selected-template
  (let [initial (core/project-wizard-state example-global-config)
        [library _] (core/update-fn initial (msg/key-press :enter))
        [app _] (core/update-fn (assoc initial :template-idx 1)
                                (msg/key-press :enter))]
    (is (= "my-lib" (get-in library [:project-name :placeholder])))
    (is (str/includes? (core/strip-ansi (core/view library)) "Library name"))
    (is (= "my-app" (get-in app [:project-name :placeholder])))
    (is (str/includes? (core/strip-ansi (core/view app)) "Project name"))))

(deftest saved-groups-are-a-closed-picker-with-a-project-name-only-choice
  (let [state (assoc (core/project-wizard-state example-global-config)
                     :step :group
                     :project-name (text-input/text-input :value "my-lib"))
        rendered (core/strip-ansi (core/view state))
        [without-group _] (core/update-fn state (msg/key-press :down))
        [advanced _] (core/update-fn without-group (msg/key-press :enter))]
    (is (str/includes? rendered "Group"))
    (is (str/includes? rendered "Group ID for artifact (io.github.gbelson, com.hooli)"))
    (is (str/includes? rendered "io.github.example"))
    (is (str/includes? rendered "Use project name only"))
    (is (= 1 (:group-idx without-group)))
    (is (= :source-layout (:step advanced)))
    (is (= "my-lib" (:name (core/collect-results advanced))))))

(deftest missing-groups-offer-entry-or-a-project-name-only-identity
  (let [state (assoc (core/project-wizard-state {})
                     :step :group
                     :project-name (text-input/text-input :value "my-lib"))
        rendered (core/strip-ansi (core/view state))
        [entry _] (core/update-fn state (msg/key-press :enter))
        [choices _] (core/update-fn entry (msg/key-press :escape))
        [project-only _] (core/update-fn choices (msg/key-press :down))
        [advanced _] (core/update-fn project-only (msg/key-press :enter))
        confirmation (core/strip-ansi
                      (core/view (assoc advanced :step :confirm)))]
    (is (str/includes? rendered "Enter a group (Recommended)"))
    (is (str/includes? rendered "Use project name only"))
    (is (true? (:group-entry? entry)))
    (is (false? (:group-entry? choices)))
    (is (= :source-layout (:step advanced)))
    (is (= "my-lib" (:name (core/collect-results advanced))))
    (is (str/includes? confirmation "Identity:"))
    (is (str/includes? confirmation "my-lib"))))

(deftest explicit-groups-are-validated-and-qualify-project-identity
  (let [base (assoc (core/project-wizard-state {})
                    :step :group
                    :group-entry? true
                    :project-name (text-input/text-input :value "my-lib"))
        invalid-groups ["Acme.tools" "acme" "acme..tools" "acme.2tools"]]
    (doseq [group-name invalid-groups]
      (let [[rejected _]
            (core/update-fn
             (assoc base :group-name (text-input/text-input :value group-name))
             (msg/key-press :enter))]
        (is (= :group (:step rejected)))
        (is (str/includes? (core/strip-ansi (:error rejected))
                           "Group requirements"))))
    (let [[accepted _]
          (core/update-fn
           (assoc base
                  :group-name
                  (text-input/text-input :value "io.github.my-name"))
           (msg/key-press :enter))
          request (core/collect-results accepted)
          confirmation (core/strip-ansi
                        (core/view (assoc accepted :step :confirm)))]
      (is (= :source-layout (:step accepted)))
      (is (= "io.github.my-name/my-lib" (:name request)))
      (is (str/includes? confirmation "io.github.my-name/my-lib")))))

(deftest source-namespace-layouts-render-dynamic-examples-and-shape-generation
  (let [state (assoc (core/project-wizard-state example-global-config)
                     :step :source-layout
                     :project-name (text-input/text-input :value "foo"))
        rendered (core/strip-ansi (core/view state))
        rooted-request (core/collect-results state)
        [group-derived _] (core/update-fn state (msg/key-press :down))
        group-derived-request (core/collect-results group-derived)
        [advanced _] (core/update-fn group-derived (msg/key-press :enter))]
    (is (str/includes? rendered "Project-rooted (Recommended)"))
    (is (str/includes? rendered
                       "Project-rooted (Recommended)   src/foo/core.clj"))
    (is (str/includes? rendered
                       "Group-derived                  src/io/github/example/foo.clj"))
    (is (not (str/includes? rendered "foo.core")))
    (is (str/includes? rendered "src/foo/core.clj"))
    (is (str/includes? rendered "Group-derived"))
    (is (not (str/includes? rendered "io.github.example.foo")))
    (is (str/includes? rendered "src/io/github/example/foo.clj"))
    (is (= "io.github.example/foo" (:name rooted-request)))
    (is (= "foo" (:top rooted-request)))
    (is (= "core" (:main rooted-request)))
    (is (= "io.github.example/foo" (:name group-derived-request)))
    (is (not (contains? group-derived-request :top)))
    (is (not (contains? group-derived-request :main)))
    (is (= :developer (:step advanced)))))

(deftest developer-is-optional-without-saved-developers
  (let [blank-state (assoc (core/project-wizard-state {})
                           :step :developer
                           :project-name (text-input/text-input :value "my-lib"))
        blank-rendered (core/strip-ansi (core/view blank-state))
        [blank-advanced _] (core/update-fn blank-state (msg/key-press :enter))
        blank-request (core/collect-results blank-advanced)
        blank-confirmation (core/strip-ansi
                            (core/view (assoc blank-advanced :step :confirm)))
        entered-state (assoc blank-state
                             :developer (text-input/text-input :value "Jane Doe"))
        [entered-advanced _] (core/update-fn entered-state (msg/key-press :enter))
        entered-request (core/collect-results entered-advanced)
        entered-confirmation (core/strip-ansi
                              (core/view (assoc entered-advanced :step :confirm)))]
    (is (str/includes? blank-rendered "Leave blank to use the system default"))
    (is (str/includes? blank-rendered
                       (str "system default: \""
                            (core/system-default-developer) "\"")))
    (is (= :description (:step blank-advanced)))
    (is (not (contains? blank-request :developer)))
    (is (str/includes? blank-confirmation
                       (str (or (System/getenv "USER")
                                (System/getProperty "user.name"))
                            " (system default)")))
    (is (str/includes? (core/view (assoc blank-advanced :step :confirm))
                       (style/secondary " (system default)")))
    (is (= "Jane Doe" (:developer entered-request)))
    (is (str/includes? entered-confirmation "Jane Doe"))))

(deftest saved-developers-remain-a-closed-picker
  (let [state (assoc (core/project-wizard-state example-global-config)
                     :step :developer)
        [selected _] (core/update-fn state (msg/key-press :enter))]
    (is (= :description (:step selected)))
    (is (= "Jane Developer" (:developer (core/collect-results selected))))))

(deftest main-menu-routes-to-project-repl-and-resource-actions
  (with-redefs [core/dev-success-sequence? false
                core/dev-opening-sequence? false]
    (let [global-config example-global-config
          initial       (core/main-menu-state global-config)
          [project _]   (core/update-fn initial (msg/key-press :enter))
          [resources _] (core/update-fn (assoc initial :menu-idx 2)
                                        (msg/key-press :enter))
          opened        (atom nil)
          [category _]
          (with-redefs [core/open-url! (fn [url]
                                         (reset! opened url)
                                         true)]
            (let [[category _] (core/update-fn resources (msg/key-press :enter))]
              (core/update-fn category (msg/key-press :enter))))
          [repl repl-cmd] (core/update-fn (assoc initial :menu-idx 1)
                                          (msg/key-press :enter))
          rendered       (core/strip-ansi (core/view initial))]
      (is (= :project-template (:step project)))
      (is (= :resources (:step resources)))
      (is (= [data/community-resources] (:resource-stack resources)))
      (is (= "https://clojure.org/" @opened))
      (is (= :resources (:step category)))
      (is (= :repl-menu (:step repl)))
      (is (zero? (:menu-idx repl)))
      (is (nil? repl-cmd))
      (is (str/includes? rendered "New Project Wizard"))
      (is (str/includes? rendered "Launch Interactive REPL")))))

(deftest development-success-sequence-can-be-played-from-the-main-menu
  (let [initial (core/main-menu-state example-global-config)]
    (with-redefs [core/dev-success-sequence? false]
      (is (not (str/includes? (core/strip-ansi (core/view initial))
                              "Play Success Sequence"))))
    (with-redefs [core/dev-success-sequence? true]
      (let [rendered (core/strip-ansi (core/view initial))
            [started command] (core/update-fn initial (msg/key-press :enter))
            [returned completion-command]
            (core/update-fn
             (assoc started
                    :confetti {:animation :polar :frame 0 :tracks []})
             (msg/key-press "confetti-tick"))]
        (is (= "Play Success Sequence" (first (core/main-menu-items))))
        (is (str/includes? rendered "Play Success Sequence"))
        (is (false? (:success-pause started)))
        (is (true? (:success-preview? started)))
        (is (map? (:confetti started)))
        (is (not (str/includes? (core/strip-ansi (core/view started))
                                "Project created at")))
        (is (= :cmd (:type command)))
        (is (= :main-menu (:step returned)))
        (is (zero? (:menu-idx returned)))
        (is (nil? (:confetti returned)))
        (is (false? (:done? returned)))
        (is (nil? completion-command))))))

(deftest development-opening-sequence-can-be-replayed-from-the-main-menu
  (let [initial (core/main-menu-state example-global-config)]
    (with-redefs [core/dev-success-sequence? false
                  core/dev-opening-sequence? false]
      (is (not (str/includes? (core/strip-ansi (core/view initial))
                              "Replay Opening Sequence"))))
    (with-redefs [animation/opening-animation? false
                  core/dev-success-sequence? true
                  core/dev-opening-sequence? true
                  animation/confetti-animation :polar]
      (let [choices (core/main-menu-choices)
            replay-index (.indexOf choices :opening-sequence)
            rendered (core/strip-ansi (core/view initial))
            [started command] (core/update-fn (assoc initial
                                                     :menu-idx replay-index)
                                              (msg/key-press :enter))]
        (is (= ["Play Success Sequence" "Replay Opening Sequence"]
               (subvec (core/main-menu-items) 0 2)))
        (is (str/includes? rendered "Replay Opening Sequence"))
        (is (= :confetti (get-in started [:opening-animation :phase])))
        (is (= :in (get-in started
                           [:opening-animation :confetti :direction])))
        (is (= animation/reverse-confetti-total-frames
               (get-in started [:opening-animation :confetti :frame-count])))
        (is (= :cmd (:type command)))))))

(deftest main-menu-escape-shows-the-ctrl-c-quit-reminder
  (let [initial (core/main-menu-state example-global-config)
        [escaped command] (core/update-fn initial (msg/key-press :escape))]
    (is (= :main-menu (:step escaped)))
    (is (= "Press Ctrl-C to quit." (:error escaped)))
    (is (nil? command))
    (let [rendered (core/strip-ansi (core/view escaped))]
      (is (str/includes? rendered "\n  Press Ctrl-C to quit."))
      (is (not (str/includes? rendered "\n\n  Press Ctrl-C to quit."))))))

(deftest main-menu-navigation-clears-the-ctrl-c-quit-reminder
  (let [initial (assoc (core/main-menu-state example-global-config)
                       :error "Press Ctrl-C to quit.")
        [moved-up _] (core/update-fn initial (msg/key-press :up))
        [moved-down _] (core/update-fn initial (msg/key-press :down))]
    (is (nil? (:error moved-up)))
    (is (nil? (:error moved-down)))))

(deftest community-resources-navigate-nested-entries-and-hide-urls
  (let [initial (assoc (core/main-menu-state example-global-config)
                       :step :resources
                       :resource-stack [data/community-resources]
                       :resource-labels [])
        [nested _] (core/update-fn initial (msg/key-press :enter))
        [back _] (core/update-fn nested (msg/key-press :escape))
        rendered (core/strip-ansi (core/view initial))]
    (is (= ["Language"] (:resource-labels nested)))
    (is (= "Clojure" (get-in nested [:resource-stack 1 0 :label])))
    (is (= :resources (:step back)))
    (is (= [data/community-resources] (:resource-stack back)))
    (is (str/includes? rendered "Language"))
    (is (str/includes? rendered "Explore Clojure variants and dialects"))
    (is (not (str/includes? rendered "https://clojure.org/")))))

(deftest community-resource-selection-persists-when-leaving-nested-menus
  (let [links       [{:label "Nested link" :url "https://example.test"}]
        child-items [{:label "First child" :entries links}
                     {:label "Second child" :entries links}
                     {:label "Third child" :entries links}]
        root-items  [{:label "First" :entries links}
                     {:label "Second" :entries links}
                     {:label "Third" :entries links}
                     {:label "Fourth" :entries links}
                     {:label "Fifth" :entries child-items}]
        initial     (assoc (core/main-menu-state example-global-config)
                           :step :resources
                           :resource-stack [root-items]
                           :resource-labels []
                           :resource-menu-labels [])
        update-key  (fn [state key]
                      (first (core/update-fn state (msg/key-press key))))
        fifth       (nth (iterate #(update-key % :down) initial) 4)
        child       (update-key fifth :enter)
        third       (nth (iterate #(update-key % :down) child) 2)
        nested      (update-key third :enter)
        back-child  (update-key nested :escape)
        back-root   (update-key back-child :escape)]
    (is (= 4 (:menu-idx fifth)))
    (is (zero? (:menu-idx child)))
    (is (= 2 (:menu-idx third)))
    (is (zero? (:menu-idx nested)))
    (is (= 2 (:menu-idx back-child)))
    (is (= 4 (:menu-idx back-root)))))

(deftest community-resource-menu-labels-follow-the-nested-menu-stack
  (let [initial (assoc (core/main-menu-state example-global-config)
                       :step :resources
                       :menu-idx 5
                       :resource-stack [data/community-resources]
                       :resource-labels []
                       :resource-menu-labels [])
        [development _] (core/update-fn initial (msg/key-press :enter))
        [back _] (core/update-fn development (msg/key-press :escape))
        rendered (core/strip-ansi (core/view development))]
    (is (= "Select a dialect" (peek (:resource-menu-labels development))))
    (is (str/includes? rendered
                       "☯ jus ╱ Community Resources ╱ Development"))
    (is (str/includes? rendered "Select a dialect"))
    (is (empty? (:resource-menu-labels back)))
    (is (not (str/includes? (core/strip-ansi (core/view back))
                            "Select a dialect")))))

(deftest active-resource-shows-its-url-below-the-global-column-gap
  (let [state (assoc (core/main-menu-state example-global-config)
                     :step :resources
                     :term-width 50
                     :resource-stack [[{:label "A resource"
                                        :desc "A description that is too long for this narrow menu"
                                        :url "https://example.test"}]]
                     :resource-labels [])
        rendered (core/strip-ansi (core/view state))]
    (is (str/includes? rendered "A resource   A description"))
    (is (str/includes? rendered "... ↗"))
    (is (str/includes? rendered "https://example.test"))
    (is (= (count " ╭──────────────────────────────────────────────╮")
           (count (second (str/split-lines
                           (core/strip-ansi
                            (core/render-resource-list
                             [{:label "A resource"
                               :desc "A description that is too long for this narrow menu"}]
                             0
                             50)))))))))

(deftest new-project-opens-the-project-wizard-and-returns-to-main-menu
  (with-redefs [core/dev-success-sequence? false
                core/dev-opening-sequence? false]
    (let [initial (core/main-menu-state example-global-config)
          [project _] (core/update-fn initial (msg/key-press :enter))
          [back _] (core/update-fn project (msg/key-press :escape))]
      (is (= :project-template (:step project)))
      (is (= :main-menu (:step back))))))

(deftest wizard-reserves-arrow-keys-for-text-input-caret-movement
  (let [state (assoc (core/project-wizard-state example-global-config)
                     :step :project-name
                     :project-name (text-input/text-input :value "abc" :focused true))
        [left _] (core/update-fn state (msg/key-press :left))
        [right _] (core/update-fn left (msg/key-press :right))
        [escape _] (core/update-fn state (msg/key-press :escape))
        license-state (assoc state :step :license)
        [license-left _] (core/update-fn license-state (msg/key-press :left))
        [license-right _] (core/update-fn license-state (msg/key-press :right))]
    (is (= :project-name (:step left)))
    (is (= 2 (get-in left [:project-name :pos])))
    (is (= :project-name (:step right)))
    (is (= 3 (get-in right [:project-name :pos])))
    (is (= :project-template (:step escape)))
    (is (= :license (:step license-left)))
    (is (= :license (:step license-right)))))

(deftest repl-menu-navigates-launches-and-returns-to-the-main-menu
  (let [initial (assoc (core/main-menu-state example-global-config)
                       :step :repl-menu)
        [down _] (core/update-fn initial (msg/key-press :down))
        [last-item _] (core/update-fn down (msg/key-press "j"))
        [clamped _] (core/update-fn last-item (msg/key-press :down))
        [up _] (core/update-fn clamped (msg/key-press "k"))
        [launch launch-cmd] (core/update-fn up (msg/key-press :enter))
        [back _] (core/update-fn down (msg/key-press :escape))
        [_ quit-cmd] (core/update-fn initial (msg/key-press "c" :ctrl true))
        rendered (core/strip-ansi (core/view initial))]
    (is (= 1 (:menu-idx down)))
    (is (= 2 (:menu-idx last-item)))
    (is (= 3 (:menu-idx clamped)))
    (is (= 2 (:menu-idx up)))
    (is (= :repl (:action launch)))
    (is (= :babashka (:repl-id launch)))
    (is (= program/quit-cmd launch-cmd))
    (is (= :main-menu (:step back)))
    (is (zero? (:menu-idx back)))
    (is (= program/quit-cmd quit-cmd))
    (is (= 130 (:exit-code (first (core/update-fn initial
                                                  (msg/key-press "c" :ctrl true))))))
    (is (str/includes? rendered "Select REPL type"))
    (is (str/includes? rendered "Clojure (default)"))
    (is (str/includes? rendered "ClojureScript (JS)"))))

(defn- final-confirmation-state [parent]
  {:step           :path-confirm-final
   :global-config  (config/project-config
                    {:groups ['io.github.example]
                     :developers ["Jane Developer"]
                     :parent-dirs []})
   :project-name   (text-input/text-input :value "my-lib")
   :description    (text-input/text-input :value "A useful library")
   :template-idx   0
   :group-idx      0
   :developer-idx  0
   :license-idx    0
   :max-step-idx   10
   :path-mode      "browse"
   :nav-path       parent
   :final-idx      0
   :results        {}
   :global-config-exists? true
   :done?          false
   :confetti       nil
   :term-width     80
   :error          nil})

(deftest final-confirmation-starts-captured-generation-asynchronously
  (let [parent  (str (Files/createTempDirectory
                      "jus-core-test-"
                      (make-array FileAttribute 0)))
        target  (str parent "/my-lib")
        handle  {:process :fake}
        started (atom nil)]
    (try
      (with-redefs [generator/start! (fn [request]
                                       (reset! started request)
                                       handle)]
        (let [[state command]
              (core/update-fn (final-confirmation-state parent)
                              (msg/key-press :enter))]
          (is (= {:template    "lib"
                  :name        "io.github.example/my-lib"
                  :target-dir  target
                  :developer   "Jane Developer"
                  :description "A useful library"
                  :license/id  "EPL-2.0"
                  :top         "my-lib"
                  :main        "core"
                  :build       :bb}
                 @started))
          (is (= handle (get-in state [:generation :handle])))
          (is (= target (get-in state [:generation :target-dir])))
          (is (= :batch (:type command)))
          (is (str/includes? (core/strip-ansi (core/view state))
                             "Creating new project..."))))
      (finally
        (generator/cleanup! parent)))))

(deftest running-generation-animates-and-success-enters-the-preserved-pause
  (let [target  "/tmp/jus-success/my-lib"
        request {:template   "lib"
                 :name       "io.github.example/my-lib"
                 :target-dir target
                 :developer  "Jane Developer"
                 :license/id "MIT"
                 :build      :bb}
        state   (assoc (final-confirmation-state "/tmp/jus-success")
                       :results request
                       :generation {:handle     {:process :fake}
                                    :target-dir target
                                    :frame      0})
        [animated tick-command]
        (core/update-fn state (msg/key-press "generation-tick"))
        [succeeded pause-command]
        (core/update-fn animated
                        {:type :generation-complete
                         :result {:exit-code 0
                                  :out "captured generator output"
                                  :err ""}})]
    (is (= 1 (get-in animated [:generation :frame])))
    (is (= :cmd (:type tick-command)))
    (is (nil? (:generation succeeded)))
    (is (true? (:success-pause succeeded)))
    (is (= :cmd (:type pause-command)))
    (is (str/includes? (core/view succeeded) "Project created at"))
    (is (not (str/includes? (core/view succeeded) "captured generator output")))))

(deftest missing-startup-config-is-offered-after-generation-before-success
  (let [target "/tmp/jus-config-offer/my-lib"
        state  (-> (final-confirmation-state "/tmp/jus-config-offer")
                   (assoc :global-config-exists? false
                          :global-config {}
                          :group-entry? true
                          :group-name (text-input/text-input :value "io.github.example")
                          :developer (text-input/text-input :value "Jane Developer")
                          :results {:target-dir target}
                          :generation {:handle {:process :fake}
                                       :target-dir target
                                       :frame 0}))
        [offered _]
        (core/update-fn state {:type :generation-complete
                               :result {:exit-code 0 :out "" :err ""}})
        rendered (core/strip-ansi (core/view offered))
        [creating create-command]
        (core/update-fn offered (msg/key-press :enter))
        [created pause-command]
        (core/update-fn creating {:type :config-creation-complete
                                  :result {:status :created}})]
    (is (= :config-offer (:step offered)))
    (is (= (config/project-config
            {:groups ['io.github.example]
             :developers ["Jane Developer"]
             :parent-dirs ["/tmp/jus-config-offer"]})
           (get-in offered [:config-offer :config])))
    (is (str/includes? rendered "Setup a wizard config to use going forward?"))
    (is (str/includes? rendered "Yes, create a wizard config.edn (Recommended)"))
    (is (str/includes? rendered "Skip for now"))
    (is (str/includes? rendered "io.github.example"))
    (is (str/includes? rendered "/tmp/jus-config-offer"))
    (is (str/includes? rendered "This will create `~/.config/jus/config.edn`."))
    (is (str/includes? rendered "Once created, you can manually add more groups, devs, or dirs."))
    (is (str/includes? rendered "  {:groups [io.github.example]"))
    (is (str/includes? rendered "   :developers [\"Jane Developer\"]"))
    (is (map? (:config-creation creating)))
    (is (= :cmd (:type create-command)))
    (is (true? (:success-pause created)))
    (is (true? (:global-config-exists? created)))
    (is (= :cmd (:type pause-command)))))

(deftest config-offer-skips-or-retries-with-the-preview-intact
  (let [offer {:path "/tmp/jus-config-offer/config.edn"
               :config (config/project-config
                        {:groups ['io.github.example]
                         :developers ["Jane Developer"]
                         :parent-dirs ["/tmp/jus-config-offer"]})}
        state (assoc (final-confirmation-state "/tmp/jus-config-offer")
                     :step :config-offer
                     :config-offer offer)
        [skipped skip-command] (core/update-fn state (msg/key-press :escape))
        [ctrl-skipped ctrl-skip-command]
        (core/update-fn state (msg/key-press "c" :ctrl true))
        [skip-selected _] (core/update-fn state (msg/key-press :down))
        [selected-skip selected-skip-command]
        (core/update-fn skip-selected (msg/key-press :enter))
        [creating _] (core/update-fn state (msg/key-press :enter))
        [failed _] (core/update-fn creating
                                   {:type :config-creation-complete
                                    :error (ex-info "permission denied" {})})
        [message-less-failed _]
        (core/update-fn creating
                        {:type :config-creation-complete
                         :error (ex-info nil {})})
        rendered (core/strip-ansi (core/view failed))
        skipped-rendered (core/view selected-skip)
        message-less-rendered (core/strip-ansi (core/view message-less-failed))
        [retried retry-command] (core/update-fn failed (msg/key-press :enter))]
    (is (false? (:success-pause skipped)))
    (is (map? (:confetti skipped)))
    (is (= :cmd (:type skip-command)))
    (is (false? (:success-pause ctrl-skipped)))
    (is (map? (:confetti ctrl-skipped)))
    (is (= :cmd (:type ctrl-skip-command)))
    (is (false? (:success-pause selected-skip)))
    (is (map? (:confetti selected-skip)))
    (is (= :cmd (:type selected-skip-command)))
    (is (= (animation/render-confetti (:confetti selected-skip)
                                      (:term-width selected-skip)
                                      (:term-height selected-skip))
           skipped-rendered))
    (is (= :config-offer (:step failed)))
    (is (= offer (:config-offer failed)))
    (is (str/includes? rendered "Retry creating a wizard config.edn"))
    (is (str/includes? rendered "permission denied"))
    (is (str/includes? message-less-rendered "Retry creating a wizard config.edn"))
    (is (str/includes? rendered "io.github.example"))
    (is (map? (:config-creation retried)))
    (is (= :cmd (:type retry-command)))))

(deftest spinner-frames-use-raw-dim-styling
  (let [generation-view (core/view {:generation {:frame 2
                                                 :target-dir "/tmp/demo"}})]
    (is (= "\033[2m───\033[0m"
           (style/dim "───")))
    (is (= ["☯ " "☯ " "  " "☯ "]
           (mapv core/strip-ansi core/loading-spinner-frames)))
    (is (str/includes? (core/strip-ansi generation-view) "Creating new project..."))
    (is (str/includes? (core/strip-ansi generation-view) "Creating new project..."))))

(deftest generator-failure-cleans-partial-output-and-can-be-retried
  (let [parent  "/tmp/jus-retry"
        target  (str parent "/my-lib")
        request {:template   "lib"
                 :name       "io.github.example/my-lib"
                 :target-dir target
                 :developer  "Jane Developer"
                 :description "A useful library"
                 :license/id "EPL-2.0"
                 :top        "my-lib"
                 :main       "core"
                 :build      :bb}
        state   (assoc (final-confirmation-state parent)
                       :results request
                       :generation {:handle     {:process :first}
                                    :target-dir target
                                    :frame      3})
        cleaned (atom [])
        started (atom nil)]
    (with-redefs [generator/cleanup! (fn [path] (swap! cleaned conj path))
                  generator/start!   (fn [retried-request]
                                       (reset! started retried-request)
                                       {:process :retry})]
      (let [[failed failure-command]
            (core/update-fn state
                            {:type :generation-complete
                             :result {:exit-code 2
                                      :out "captured stdout"
                                      :err "  dependency resolution failed  \n"}})
            [retried retry-command]
            (core/update-fn failed (msg/key-press :enter))]
        (is (= [target] @cleaned))
        (is (nil? failure-command))
        (is (nil? (:generation failed)))
        (is (= :path-confirm-final (:step failed)))
        (is (str/includes? (:error failed) "dependency resolution failed"))
        (is (str/includes? (core/view failed) "dependency resolution failed"))
        (is (= request @started))
        (is (= {:process :retry} (get-in retried [:generation :handle])))
        (is (nil? (:error retried)))
        (is (= :batch (:type retry-command)))))))

(deftest ctrl-c-terminates-generation-cleans-the-target-and-exits-130
  (let [handle    {:process :running}
        target    "/tmp/jus-cancel/my-lib"
        state     (assoc (final-confirmation-state "/tmp/jus-cancel")
                         :generation {:handle     handle
                                      :target-dir target
                                      :frame      4})
        cancelled (atom [])
        cleaned   (atom [])]
    (with-redefs [generator/cancel!  (fn [running-handle]
                                       (swap! cancelled conj running-handle))
                  generator/cleanup! (fn [path]
                                       (swap! cleaned conj path))]
      (let [[cancelled-state command]
            (core/update-fn state (msg/key-press "c" :ctrl true))]
        (is (= [handle] @cancelled))
        (is (= [target] @cleaned))
        (is (nil? (:generation cancelled-state)))
        (is (= 130 (:exit-code cancelled-state)))
        (is (= program/quit-cmd command))))))

(deftest blank-description-renders-and-defers-to-the-generator-default
  (let [state   (-> (final-confirmation-state "/tmp/jus-blank")
                    (assoc :step :confirm)
                    (assoc :description (text-input/text-input :value "  ")))
        request (core/collect-results state)
        rendered (core/view (assoc state :step :description))
        summary-rendered (core/view state)]
    (is (not (contains? request :description)))
    (is (str/includes? rendered "Enter to skip description"))
    (is (str/includes? summary-rendered "Description:"))
    (is (str/includes? summary-rendered "Identity:"))))

(deftest project-details-emphasizes-the-value-column
  (let [state (assoc (final-confirmation-state "/tmp/jus-details")
                     :step :confirm)
        rendered (core/view state)]
    (is (str/includes? rendered
                       (str "Type:            " (style/primary "Library"))))
    (is (str/includes? rendered
                       (str "Identity:        " (style/primary "io.github.example/my-lib"))))
    (is (str/includes? rendered
                       (str "SPDX license:    " (style/primary "EPL-2.0"))))
    (let [app-rendered (core/view (assoc state :template-idx 1))]
      (is (str/includes? app-rendered
                         (str "Type:            " (style/primary "App")))))))

(deftest location-browser-uses-tail-path-emphasis-and-resets-the-browse-choice
  (let [project-name (text-input/text-input :value "my-lib")
        browser-state {:step      :path-confirm
                       :path-mode "browse"
                       :nav-path  "/tmp/projects"
                       :nav-idx   0
                       :nav-items [{:label "child/" :type :dir :path "/tmp/projects/child"}]
                       :project-name project-name
                       :max-step-idx 9
                       :term-width 80}
        browser-rendered (core/view browser-state)
        [down _] (core/update-fn browser-state (msg/key-press :down))
        [back _] (core/update-fn down (msg/key-press :escape))
        project-path-rendered (core/strip-ansi (core/view back))
        final-rendered (core/view (assoc (final-confirmation-state "/tmp/projects")
                                         :nav-path "/tmp/projects"
                                         :project-name project-name))]
    (is (str/includes? browser-rendered (style/primary "my-lib")))
    (is (not (str/includes? browser-rendered
                            (style/primary "/tmp/projects/my-lib"))))
    (is (str/includes? final-rendered (style/primary "my-lib")))
    (is (not (str/includes? final-rendered
                            (style/primary "/tmp/projects/my-lib"))))
    (is (= :project-path (:step back)))
    (is (zero? (:nav-idx back)))
    (is (str/includes? project-path-rendered "> Browse file tree..."))))

(deftest configured-home-relative-parent-dirs-resolve-before-confirmation
  (let [configured-parent "~/hooli/projects"
        state (assoc (final-confirmation-state configured-parent)
                     :step :parent-dir-select
                     :parent-dirs-idx 0
                     :global-config (config/project-config
                                     {:groups ['io.github.example]
                                      :developers ["Jane Developer"]
                                      :parent-dirs [configured-parent]}))
        [confirmed _] (core/update-fn state (msg/key-press "enter"))
        home (System/getProperty "user.home")
        expected-parent (str home "/hooli/projects")]
    (is (= :path-confirm-final (:step confirmed)))
    (is (= expected-parent (:nav-path confirmed)))
    (is (= (str expected-parent "/my-lib")
           (:target-dir (core/collect-results confirmed))))
    (is (str/includes? (core/strip-ansi (core/view confirmed))
                       "> ~/hooli/projects/my-lib"))))

(deftest adobe-profile-suffix-is-hidden-from-directory-labels
  (is (= "Creative Cloud Files"
         (core/display-directory-name
          "Creative Cloud Files  JCOYLE@GMAIL.COM 3D1A6263427E46F7992015D5@AdobeID")))
  (is (= "ordinary-dir"
         (core/display-directory-name "ordinary-dir"))))

(deftest existing-target-is-rejected-without-starting-generation
  (let [parent  (str (Files/createTempDirectory
                      "jus-collision-test-"
                      (make-array FileAttribute 0)))
        target  (str parent "/my-lib")
        sentinel (str target "/keep.txt")
        started? (atom false)]
    (try
      (.mkdirs (java.io.File. target))
      (spit sentinel "keep")
      (with-redefs [generator/start! (fn [_]
                                       (reset! started? true)
                                       {:process :unexpected})]
        (let [[state command]
              (core/update-fn (final-confirmation-state parent)
                              (msg/key-press :enter))]
          (is (false? @started?))
          (is (nil? command))
          (is (nil? (:generation state)))
          (is (str/includes? (:error state) "Already exists"))
          (is (= "keep" (slurp sentinel)))))
      (finally
        (generator/cleanup! parent)))))

(deftest successful-generation-preserves-confetti-and-completion-hints
  (let [target "/tmp/jus-finished/my-lib"
        paused {:step          :path-confirm-final
                :results       {:target-dir target :template "lib"}
                :success-pause true
                :confetti      nil
                :done?         false
                :term-width    80
                :term-height   24}
        [celebrating tick-command]
        (core/update-fn paused (msg/key-press "success-pause-done"))
        almost-done (assoc celebrating
                           :confetti {:animation :polar :frame 0 :tracks []})
        [blank pause-command]
        (core/update-fn almost-done (msg/key-press "confetti-tick"))
        [done quit-command]
        (core/update-fn blank
                        (msg/key-press "post-confetti-blank-screen-pause-done"))
        completion-rendered (core/view (assoc paused
                                              :success-pause false
                                              :done? true))]
    (is (false? (:success-pause celebrating)))
    (is (map? (:confetti celebrating)))
    (is (= -2 (get-in celebrating [:confetti :frame])))
    (is (= :cmd (:type tick-command)))
    (is (true? (:post-confetti-blank-screen-pause? blank)))
    (is (false? (:done? blank)))
    (is (= "" (core/view blank)))
    (is (= :cmd (:type pause-command)))
    (is (true? (:done? done)))
    (is (true? (:post-confetti-blank-screen-pause? done)))
    (is (= "" (core/view done)))
    (is (= program/quit-cmd quit-command))
    (is (str/includes? completion-rendered target))
    (is (str/includes? completion-rendered "cd "))
    (is (str/includes? completion-rendered "jus"))
    (is (str/includes? completion-rendered "tasks"))
    (is (str/includes? completion-rendered "CLOJARS_USERNAME"))
    (is (str/includes? completion-rendered "CLOJARS_PASSWORD"))
    (is (str/includes? completion-rendered "bb ci:deploy"))))

(deftest confetti-completes-after-its-last-glyph-leaves-the-viewport
  (let [confetti (with-redefs [animation/confetti-animation :polar]
                   (animation/init-confetti 80 24))
        leading-char-only-visible?
        (with-redefs [animation/confetti-ray-visible-chars 1]
          (animation/confetti-visible? {:animation :polar
                                        :frame 0
                                        :center {:row 0 :column 0}
                                        :tracks [{:id 0 :placements [[0 0]]}]}
                                       1
                                       1))
        late-frame {:term-width 80
                    :term-height 24
                    :confetti (assoc confetti
                                     :frame (- (:frame-count confetti) 2))}
        [still-celebrating tick-command]
        (core/update-fn late-frame (msg/key-press "confetti-tick"))
        [blank pause-command]
        (core/update-fn (assoc late-frame
                               :confetti (assoc (:confetti late-frame)
                                                :frame 200))
                        (msg/key-press "confetti-tick"))
        [finished quit-command]
        (core/update-fn blank
                        (msg/key-press "post-confetti-blank-screen-pause-done"))]
    (is leading-char-only-visible?)
    (is (map? (:confetti still-celebrating)))
    (is (= :cmd (:type tick-command)))
    (is (nil? (:confetti blank)))
    (is (true? (:post-confetti-blank-screen-pause? blank)))
    (is (= "" (core/view blank)))
    (is (= :cmd (:type pause-command)))
    (is (nil? (:confetti finished)))
    (is (true? (:done? finished)))
    (is (= program/quit-cmd quit-command))))

(deftest confetti-animation-and-direction-settings-are-captured
  (let [laser-rays (with-redefs [animation/confetti-animation :laser-rays]
                     (animation/init-confetti 80 24))
        polar      (with-redefs [animation/confetti-animation :polar]
                     (animation/init-confetti 80 24))
        inward-laser-rays (with-redefs [animation/confetti-animation :laser-rays
                                        animation/confetti-direction :in]
                            (animation/init-confetti 80 24))
        inward-polar (with-redefs [animation/confetti-animation :polar
                                   animation/confetti-direction :in]
                       (animation/init-confetti 80 24))]
    (is (= :polar animation/confetti-animation))
    (is (= :out animation/confetti-direction))
    (is (= 42 animation/reverse-confetti-total-frames))
    (is (= 8 animation/reverse-confetti-frame-rate))
    (is (= :laser-rays (:animation laser-rays)))
    (is (= :out (:direction laser-rays)))
    (is (seq (:particles laser-rays)))
    (is (nil? (:tracks laser-rays)))
    (is (= :polar (:animation polar)))
    (is (= :out (:direction polar)))
    (is (seq (:tracks polar)))
    (is (map? (:tracks-by-id polar)))
    (is (nil? (:particles polar)))
    (is (= :in (:direction inward-laser-rays)))
    (is (= :in (:direction inward-polar)))
    (is (= animation/reverse-confetti-total-frames (:frame-count inward-polar)))))

(deftest laser-rays-complete-after-their-last-glyph-leaves-the-viewport
  (let [confetti (with-redefs [animation/confetti-animation :laser-rays]
                   (animation/init-confetti 80 24))
        late-frame {:term-width 80
                    :term-height 24
                    :confetti (assoc confetti
                                     :frame (- animation/confetti-total-frames 2))}
        [still-celebrating tick-command]
        (core/update-fn late-frame (msg/key-press "confetti-tick"))
        [blank pause-command]
        (core/update-fn (assoc late-frame
                               :confetti (assoc confetti :frame 1000))
                        (msg/key-press "confetti-tick"))
        [finished quit-command]
        (core/update-fn blank
                        (msg/key-press "post-confetti-blank-screen-pause-done"))]
    (is (map? (:confetti still-celebrating)))
    (is (= :cmd (:type tick-command)))
    (is (nil? (:confetti blank)))
    (is (true? (:post-confetti-blank-screen-pause? blank)))
    (is (= :cmd (:type pause-command)))
    (is (nil? (:confetti finished)))
    (is (true? (:done? finished)))
    (is (= program/quit-cmd quit-command))))

(deftest addressable-confetti-grid-matches-the-handcrafted-model
  (let [reference-rows
        ["  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0"
         "d  7     3  3  2  2  2  2               1  1  1  1  1"
         "d  b  7       3  3        2  2  2  2                    1  1  1  1  1"
         "d   b    7            3  3           2  2  2  2                         1  1  1  1  1"
         "d    b      7     5           3  3              2  2  2  2"
         "d     b        7      5      4        3  3                 2  2  2  2"
         "d      b   9      7       5       4           3  3                    2  2  2  2"
         "d   c   b    9       7         5        4             3  3                       2  2  2  2"
         "d        b     9        7   6       5         4               3  3"
         "d    c    b      9   8     7    6        5          4                 3  3"
         "d          b   a   9          7     6         5           4                    3  3"
         "d     c     b        9     8     7      6          5            4                       3  3"
         "d            b    a    9            7       6           5             4"
         "d      c      b          9      8      7        6            5              4"
         "d              b     a     9              7         6             5               4"
         "d       c       b            9       8       7          6              5                4"
         "d                b      a      9                7           6               5"
         "d        c        b              9        8        7            6                5"
         "d                  b       a       9                  7             6                 5"
         "d         c         b                9         8         7              6"
         "d                    b        a        9                    7               6"
         "d          c          b                  9           8         7                6"
         "d                      b         a         9                      7                 6"
         "d           c           b                    9            8          7"
         "d                        b          a          9                        7"]
        reference-height (count reference-rows)
        reference-width 92
        expected-cells
        (into #{}
              (mapcat (fn [row line]
                        (keep-indexed
                         (fn [column ch]
                           (when-not (= \space ch)
                             [(Character/digit ch 36) row column]))
                         line))
                      (range)
                      reference-rows))
        grid (with-redefs [animation/confetti-center-point {:row 0 :column 0}
                           animation/sparse-ray? false]
               (animation/confetti-grid reference-width reference-height))
        actual-cells
        (into #{}
              (comp (mapcat (fn [{:keys [id placements]}]
                              (map (fn [[row column]] [id row column])
                                   placements)))
                    (remove (fn [[_ row column]]
                              (and (zero? row) (zero? column)))))
              (:tracks grid))]
    (is (= {:row 0 :column 0} (:center grid)))
    (is (= expected-cells actual-cells))))

(deftest sparse-confetti-drops-the-selected-numeric-track-parity
  (let [full (with-redefs [animation/sparse-ray? false]
               (animation/confetti-grid 80 24))
        drop-odd (with-redefs [animation/sparse-ray? true
                               animation/sparse-drop-even? false]
                   (animation/confetti-grid 80 24))
        drop-even (with-redefs [animation/sparse-ray? true
                                animation/sparse-drop-even? true]
                    (animation/confetti-grid 80 24))]
    (is (= (filterv even? (mapv :id (:tracks full)))
           (mapv :id (:tracks drop-odd))))
    (is (= (filterv odd? (mapv :id (:tracks full)))
           (mapv :id (:tracks drop-even))))
    (is (= (count (:tracks full))
           (+ (count (:tracks drop-odd))
              (count (:tracks drop-even)))))))

(deftest addressable-confetti-grid-supports-any-center-and-viewport
  (let [width 20
        height 10
        grid (with-redefs [animation/confetti-center-point {:row 4 :column 7}
                           animation/sparse-ray? false]
               (animation/confetti-grid width height))
        tracks (:tracks grid)]
    (is (= {:row 1 :column 2} animation/confetti-center-point))
    (is (= {:row 4 :column 7} (:center grid)))
    (is (= (vec (range 52)) (mapv :id tracks)))
    (is (every? integer? (map :id tracks)))
    (is (every? #(= [4 7] (first (:placements %))) tracks))
    (is (= [4 18] (get-in grid [:tracks-by-id 0 :endpoint])))
    (is (= [9 7] (get-in grid [:tracks-by-id 13 :endpoint])))
    (is (= [4 2] (get-in grid [:tracks-by-id 26 :endpoint])))
    (is (= [0 7] (get-in grid [:tracks-by-id 39 :endpoint])))
    (is (every? (fn [[row column]]
                  (and (<= 0 row (dec height))
                       (<= 0 column (dec width))))
                (mapcat :placements tracks)))))

(deftest addressable-confetti-grid-extends-the-reference-tracks
  (let [grid (with-redefs [animation/confetti-center-point {:row 0 :column 0}
                           animation/sparse-ray? false]
               (animation/confetti-grid 120 40))]
    (is (some #{[0 92]} (get-in grid [:tracks-by-id 0 :placements])))
    (is (some #{[25 75]} (get-in grid [:tracks-by-id 7 :placements])))
    (is (some #{[25 0]} (get-in grid [:tracks-by-id 13 :placements])))))

(deftest inward-polar-rays-enter-from-the-edge-and-converge-together
  (let [confetti {:animation :polar
                  :direction :in
                  :center {:row 2 :column 2}
                  :tracks [{:id 0
                            :placements [[2 2] [2 3] [2 4] [2 5] [2 6]]}
                           {:id 13
                            :placements [[2 2] [3 2] [4 2]]}]}
        glyphs-at (fn [frame]
                    (-> (animation/render-confetti (assoc confetti :frame frame) 7 7)
                        rendered-glyph-cells
                        set))
        frame-for (fn [track-frame]
                    (long (Math/ceil
                           (/ (* track-frame
                                 (dec animation/reverse-confetti-total-frames))
                              19.0))))
        at-edge (glyphs-at 0)
        approaching (glyphs-at (frame-for 3))
        converged (glyphs-at (frame-for 4))]
    (is (= #{[2 6 \●]} at-edge))
    (is (= #{[2 3 \●] [3 2 \●]}
           (set (filter #(= \● (peek %)) approaching))))
    (is (= #{[2 2 \●]}
           (set (filter #(= \● (peek %)) converged))))
    (is (some #{[2 3 \☯]} converged))
    (is (some #{[3 2 \☯]} converged))
    (let [long-track (mapv (fn [column] [0 column]) (range 374))
          final-frame (-> (animation/render-confetti
                           {:animation :polar
                            :direction :in
                            :frame (dec animation/reverse-confetti-total-frames)
                            :center {:row 0 :column 0}
                            :tracks [{:id 0 :placements long-track}]}
                           400
                           1)
                          rendered-glyph-cells)]
      (is (= [[0 0 \☯]] final-frame)))))

(deftest laser-rays-preserve-the-pre-polar-animation
  (let [width 80
        height 24
        particles (:particles
                   (with-redefs [animation/confetti-animation :laser-rays
                                 animation/sparse-ray? false]
                     (animation/init-confetti width height)))
        east {:angle 0.0}
        south {:angle (/ Math/PI 2.0)}
        east-rendered (animation/render-confetti
                       {:animation :laser-rays
                        :frame (dec animation/confetti-total-frames)
                        :particles [east]}
                       width height)
        east-lines (-> east-rendered core/strip-ansi (str/split #"\n" -1))
        east-glyphs (->> (nth east-lines 1)
                         (keep-indexed (fn [column ch]
                                         (when (not= \space ch) [column ch])))
                         vec)
        south-after-leaving
        (animation/render-confetti {:animation :laser-rays
                                    :frame 200
                                    :particles [south]}
                                   width height)]
    (is (= 24 (count particles)))
    (is (= (map #(* Math/PI (/ % 180.0)) (range 0 360 15))
           (map :angle particles)))
    (is (= 24 (count (animation/adaptive-ray-particles particles 0))))
    (is (= 30 (count (animation/adaptive-ray-particles particles 22))))
    (is (= [19 31 37 43 46 49 52 55 58 61 64 67 70 73 76 79]
           (mapv first east-glyphs)))
    (is (= (conj (vec (repeat (dec animation/confetti-ray-visible-chars) \☯)) \●)
           (mapv second east-glyphs)))
    (is (every? #{\space}
                (mapcat seq (str/split south-after-leaving #"\n" -1))))))

(deftest sparse-laser-rays-drop-the-selected-ray-parity
  (let [drop-odd (with-redefs [animation/confetti-animation :laser-rays
                               animation/sparse-ray? true
                               animation/sparse-drop-even? false]
                   (animation/init-confetti 80 24))
        drop-even (with-redefs [animation/confetti-animation :laser-rays
                                animation/sparse-ray? true
                                animation/sparse-drop-even? true]
                    (animation/init-confetti 80 24))]
    (is (= (map #(* Math/PI (/ % 180.0)) (range 0 360 30))
           (map :angle (:particles drop-odd))))
    (is (= (map #(* Math/PI (/ % 180.0)) (range 15 360 30))
           (map :angle (:particles drop-even))))
    (is (= 12 (count (:particles drop-odd))))
    (is (= 12 (count (:particles drop-even))))))

(deftest laser-rays-use-their-original-terminal-cell-aspect-ratio
  (let [lines (-> (animation/render-confetti
                   {:animation :laser-rays
                    :frame 10
                    :particles [(animation/make-particle 45)]}
                   80 24)
                  core/strip-ansi
                  (str/split #"\n" -1))]
    (is (= \● (nth (nth lines 3) 6)))
    (is (= \space (nth (nth lines 4) 6)))))

(deftest inward-laser-rays-enter-from-the-edge-and-converge-at-the-center
  (let [confetti {:animation :laser-rays
                  :direction :in
                  :adaptive-rays? false
                  :particles [{:angle 0.0}]}
        glyphs-at (fn [frame]
                    (->> (animation/render-confetti (assoc confetti :frame frame)
                                                    80
                                                    24)
                         rendered-glyph-cells
                         (keep (fn [[row column ch]]
                                 (when (= 1 row)
                                   [column ch])))
                         vec))
        at-edge (glyphs-at 0)
        approaching (glyphs-at (- animation/reverse-confetti-total-frames 2))
        converged (glyphs-at (dec animation/reverse-confetti-total-frames))]
    (is (= [[79 \●]] at-edge))
    (is (seq approaching))
    (is (= [2 \☯] (first converged)))
    (is (every? #{\☯} (map second converged)))))

(deftest yin-yang-ray-explosion-fills-the-viewport-from-the-top-left
  (let [width     80
        height    24
        {:keys [x y bottom] :as padding}
        (animation/confetti-padding width height)
        confetti  {:animation :polar
                   :frame 0
                   :center {:row 1 :column 2}
                   :tracks [{:id 0
                             :endpoint [1 8]
                             :placements [[1 2] [1 5] [1 8]]}]}
        first-intro (core/strip-ansi
                     (animation/render-confetti (assoc confetti :frame -2) width height))
        second-intro (core/strip-ansi
                      (animation/render-confetti (assoc confetti :frame -1) width height))
        rendered  (core/strip-ansi
                   (animation/render-confetti confetti width height))
        bottom-rendered (core/strip-ansi
                         (animation/render-confetti
                          {:animation :polar
                           :frame 40
                           :center {:row 1 :column 2}
                           :tracks [{:id 0
                                     :endpoint [23 2]
                                     :placements (mapv #(vector % 2)
                                                       (range 1 24))}]}
                          width
                          height))
        first-intro-lines (str/split first-intro #"\n" -1)
        second-intro-lines (str/split second-intro #"\n" -1)
        lines     (str/split rendered #"\n" -1)
        bottom-lines (str/split bottom-rendered #"\n" -1)
        origin-y  1
        origin-x  2]
    (is (= height (count lines)))
    (is (every? #(= width (count %)) lines))
    (is (= {:x 0 :y 0 :bottom 0} padding))
    (is (= \☯ (nth (nth first-intro-lines origin-y) origin-x)))
    (is (= \☯ (nth (nth second-intro-lines origin-y) origin-x)))
    (is (= \● (nth (nth lines origin-y) origin-x)))
    (doseq [frame-lines [first-intro-lines second-intro-lines]]
      (is (= 1 (count (remove #{\space} (mapcat seq frame-lines))))))
    (is (every? #(every? #{\space} %)
                (take-last bottom lines)))
    (is (every? #{\space} (last bottom-lines)))
    (is (seq (:tracks (with-redefs [animation/confetti-animation :polar]
                        (animation/init-confetti width height)))))))

(deftest yin-yang-tracks-are-neutral-staggered-trails-that-leave-the-viewport
  (letfn [(rendered-ray-style-profile
            ([visible-chars]
             (rendered-ray-style-profile visible-chars :out))
            ([visible-chars direction]
             (with-redefs [animation/confetti-ray-visible-chars visible-chars]
               (let [dimmed-glyph (style/dim animation/confetti-ray-char)
                     glyph-pattern (re-pattern
                                    (str (java.util.regex.Pattern/quote dimmed-glyph)
                                         "|"
                                         (java.util.regex.Pattern/quote
                                          animation/confetti-ray-char)
                                         "|"
                                         (java.util.regex.Pattern/quote
                                          animation/confetti-ray-leading-char)))
                     rendered (animation/render-confetti
                               {:animation :polar
                                :direction direction
                                :frame (if (= :in direction)
                                         (long
                                          (Math/ceil
                                           (/ (* (dec visible-chars)
                                                 (dec animation/reverse-confetti-total-frames))
                                              (double (* 2
                                                         (dec visible-chars))))))
                                         (dec visible-chars))
                                :center {:row 1 :column 0}
                                :tracks [{:id 0
                                          :endpoint [1 (dec visible-chars)]
                                          :placements (mapv #(vector 1 %)
                                                            (range visible-chars))}]}
                               visible-chars
                               3)]
                 (mapv #(if (= dimmed-glyph %) :d :n)
                       (re-seq glyph-pattern rendered))))))]
    (let [width 80
          height 24
          east-track {:id 0
                      :endpoint [1 79]
                      :placements (mapv #(vector 1 %) (range 2 80 3))}
          east-rendered (animation/render-confetti {:animation :polar
                                                    :frame 25
                                                    :center {:row 1 :column 2}
                                                    :tracks [east-track]}
                                                   width height)
          east-lines (-> east-rendered core/strip-ansi (str/split #"\n" -1))
          east-glyphs (->> (nth east-lines 1)
                           (keep-indexed (fn [x ch]
                                           (when (not= \space ch) [x ch])))
                           vec)
          after-leaving (animation/render-confetti {:animation :polar
                                                    :frame 200
                                                    :center {:row 1 :column 2}
                                                    :tracks [east-track]}
                                                   width height)]
      (is (= 16 animation/confetti-ray-visible-chars))
      (is (= 8 (animation/confetti-ray-dimmed-chars)))
      (is (= [32 35 38 41 44 47 50 53 56 59 62 65 68 71 74 77]
             (mapv first east-glyphs)))
      (is (= (conj (vec (repeat (dec animation/confetti-ray-visible-chars) \☯)) \●)
             (mapv second east-glyphs)))
      (is (= [:d :d :d :d :d :d :d :d :n :d :d :n :d :n :n :n]
             (rendered-ray-style-profile 16)))
      (is (= [:n :n :n :d :n :d :d :n :d :d :d :d :d :d :d :d]
             (rendered-ray-style-profile 16 :in)))
      (is (= [:d :d :d :d :n :d :d :n]
             (rendered-ray-style-profile 8)))
      (is (= [:d :d :n :d :n]
             (rendered-ray-style-profile 5)))
      (is (= [:d :d :d :n :d :d :n]
             (rendered-ray-style-profile 7)))
      (is (= [:d :d :d :d :d :d :d :d
              :n :n :d :d :n :d :n :n :n]
             (rendered-ray-style-profile 17)))
      (is (= (vec (mapcat #(repeat 2 %)
                          [:d :d :d :d :d :d :d :d
                           :n :d :d :n :d :n :n :n]))
             (rendered-ray-style-profile 32)))
      (is (every? #{\space}
                  (mapcat seq (str/split after-leaving #"\n" -1)))))))

(deftest terminal-resizes-are-preserved-during-background-phases
  (let [resize (msg/window-size 132 41)
        laser-rays (assoc (with-redefs [animation/confetti-animation :laser-rays]
                            (animation/init-confetti 80 24))
                          :frame 2)
        opening-confetti (assoc (with-redefs [animation/confetti-animation :polar]
                                  (animation/init-confetti 80 24 :in))
                                :frame 2)
        phases [{:success-pause true}
                {:confetti {:animation :polar :frame 2 :tracks []}}
                {:confetti laser-rays}
                {:opening-animation {:phase :confetti
                                     :confetti opening-confetti}}
                {:generation {:frame 2}}
                {:config-creation {:path "/tmp/config.edn"}}]]
    (doseq [phase phases]
      (let [[resized command] (core/update-fn phase resize)]
        (is (= 132 (:term-width resized)))
        (is (= 41 (:term-height resized)))
        (when (:confetti phase)
          (is (= 2 (get-in resized [:confetti :frame])))
          (case (get-in phase [:confetti :animation])
            :polar
            (do
              (is (= 132 (get-in resized [:confetti :width])))
              (is (= 41 (get-in resized [:confetti :height])))
              (is (seq (get-in resized [:confetti :tracks]))))

            :laser-rays
            (do
              (is (= (:particles laser-rays)
                     (get-in resized [:confetti :particles])))
              (is (nil? (get-in resized [:confetti :tracks]))))))
        (when (:opening-animation phase)
          (is (= 2 (get-in resized [:opening-animation :confetti :frame])))
          (is (= :in (get-in resized
                             [:opening-animation :confetti :direction])))
          (is (= 132 (get-in resized [:opening-animation :confetti :width])))
          (is (= 41 (get-in resized [:opening-animation :confetti :height])))
          (is (= animation/reverse-confetti-total-frames
                 (get-in resized [:opening-animation :confetti :frame-count]))))
        (is (nil? command))))))

(deftest app-completion-omits-library-publishing-guidance
  (let [rendered (core/view {:done? true
                             :results {:target-dir "/tmp/jus-finished/my-app"
                                       :template "app"}
                             :term-width 80})]
    (is (not (str/includes? rendered "CLOJARS_USERNAME")))
    (is (not (str/includes? rendered "bb ci:deploy")))))

(deftest cancellation-does-not-exit-until-termination-and-cleanup-succeed
  (let [handle {:process :running}
        target "/tmp/jus-cancel-error/my-lib"
        state  (assoc (final-confirmation-state
                       "/tmp/jus-cancel-error")
                      :generation {:handle     handle
                                   :target-dir target
                                   :frame      0})]
    (let [cleanup-called? (atom false)
          [failed command]
          (with-redefs [generator/cancel! (fn [_]
                                            (throw (ex-info "still running" {})))
                        generator/cleanup! (fn [_]
                                             (reset! cleanup-called? true))]
            (core/update-fn state (msg/key-press "c" :ctrl true)))]
      (is (false? @cleanup-called?))
      (is (some? (:generation failed)))
      (is (nil? (:exit-code failed)))
      (is (nil? command))
      (is (str/includes? (core/view failed) "Unable to cancel")))
    (let [[failed command]
          (with-redefs [generator/cancel!  (fn [_] nil)
                        generator/cleanup! (fn [_]
                                             (throw (ex-info "permission denied" {})))]
            (core/update-fn state (msg/key-press "c" :ctrl true)))]
      (is (some? (:generation failed)))
      (is (nil? (:exit-code failed)))
      (is (nil? command))
      (is (str/includes? (core/view failed) "Unable to clean")))))

(deftest dangling-target-symlink-is-a-collision-and-is-not-modified
  (let [parent  (str (Files/createTempDirectory
                      "jus-symlink-collision-test-"
                      (make-array FileAttribute 0)))
        target  (str parent "/my-lib")
        missing (str parent "/missing-target")
        started? (atom false)]
    (try
      (Files/createSymbolicLink
       (Path/of target (make-array String 0))
       (Path/of missing (make-array String 0))
       (make-array FileAttribute 0))
      (with-redefs [generator/start! (fn [_]
                                       (reset! started? true)
                                       {:process :unexpected})]
        (let [[state command]
              (core/update-fn (final-confirmation-state parent)
                              (msg/key-press :enter))]
          (is (false? @started?))
          (is (nil? command))
          (is (str/includes? (:error state) "Already exists"))
          (is (Files/exists
               (Path/of target (make-array String 0))
               (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))))
      (finally
        (generator/cleanup! parent)))))

(deftest main-clears-the-console-before-launching-the-tui
  (let [target "/tmp/jus-persistent-hint/my-lib"
        final-state {:done?      true
                     :results    {:target-dir target}
                     :term-width 80}
        output (with-redefs [program/run (fn [_] final-state)]
                 (with-out-str (core/-main)))]
    (is (= "\033[H\033[2J" output))))

(deftest repl-handoff-does-not-clear-the-console-again
  (let [launched (atom nil)
        final-state {:action :repl :repl-id :babashka}]
    (with-redefs-fn {#'core/clear-console-on-launch? false
                     #'program/run (constantly final-state)
                     #'config/global-config-path (constantly "/tmp/jus-config.edn")
                     #'config/load-config-result (constantly {:config {}
                                                              :exists? false})
                     #'core/run-repl! (fn [runtime]
                                        (reset! launched runtime)
                                        0)}
      #(let [output (with-out-str (#'core/run-wizard!))]
         (is (= "" output))
         (is (= :babashka @launched))))))

(deftest cli-help-prints-usage-to-stdout
  (let [err    (java.io.StringWriter.)
        result (binding [*err* err]
                 (with-out-str
                   (is (= 0 (core/run-cli! "--help")))))]
    (is (str/includes? result "Usage: jus"))
    (is (str/includes? result "jus tasks"))
    (is (= "" (str err)))))

(deftest cli-rejects-unknown-arguments-on-stderr
  (let [err    (java.io.StringWriter.)
        output (binding [*err* err]
                 (with-out-str
                   (is (= 1 (core/run-cli! "wat")))))]
    (is (= "" output))
    (is (str/includes? (str err) "Usage: jus"))))

(deftest rebel-readline-command-uses-main-entrypoint-with-neutral-screen-theme
  (let [[_clojure _native-access _sdeps deps-edn
         main-flag main-opt module color-theme-flag color-theme]
        (core/rebel-readline-command)]
    (is (= {'com.bhauman/rebel-readline
            {:mvn/version core/rebel-readline-version}}
           (:deps (read-string deps-edn))))
    (is (= ["-M" "-m" "rebel-readline.main"
            "--color-theme" "neutral-screen-theme"]
           [main-flag main-opt module color-theme-flag color-theme]))))

(deftest repl-replaces-the-babashka-process
  (let [command  ["bb" "-cp" (#'core/repl-handoff-classpath)
                  "-m" "repl-handoff.launch" "rebel"]
        executed (atom nil)]
    (with-redefs-fn {#'core/babashka-runtime? (constantly true)
                     #'core/exec-process! #(reset! executed %)
                     #'core/run-child-process! (fn [_]
                                                 (throw (Exception. "unexpected child process")))}
      #(do
         (#'core/run-repl!)
         (is (= command @executed))
         (let [classpath (java.io.File. (nth @executed 2))]
           (is (.isAbsolute classpath))
           (is (.isDirectory classpath)))))))

(deftest repl-waits-for-a-child-process-on-the-jvm
  (with-redefs-fn {#'core/babashka-runtime? (constantly false)
                   #'core/exec-process! (fn [_]
                                          (throw (Exception. "unexpected exec")))
                   #'core/run-child-process! (constantly 23)}
    #(is (= 23 (#'core/run-repl!)))))

(deftest cli-launches-wizard-without-global-repl-preflights
  (let [checked (atom [])
        state   {:done? false}]
    (with-redefs-fn {#'core/executable-available? (fn [executable]
                                                    (swap! checked conj executable)
                                                    true)
                     #'program/run (fn [_] state)}
      #(do
         (is (= 0 (core/run-cli!)))
         (is (= [] @checked))))))

(deftest cli-defers-clojure-preflight-until-a-repl-is-selected
  (let [err     (java.io.StringWriter.)
        started (atom false)]
    (with-redefs-fn {#'core/clear-console-on-launch? false
                     #'core/executable-available? (fn [executable]
                                                    (not= "clojure" executable))
                     #'program/run (fn [_]
                                     (reset! started true)
                                     {:done? false})}
      #(let [output (binding [*err* err]
                      (with-out-str
                        (is (= 0 (core/run-cli!)))))]
         (is (= "" output))
         (is @started)
         (is (= "" (str err)))))))

(deftest cli-reports-missing-bb-for-tasks-before-discovery
  (let [err        (java.io.StringWriter.)
        discovered (atom false)]
    (with-redefs-fn {#'core/executable-available? (constantly false)
                     #'jus.tui.tasks/discover (fn [_]
                                                (reset! discovered true)
                                                {:status :ok :tasks []})}
      #(let [output (binding [*err* err]
                      (with-out-str
                        (is (= 1 (core/run-cli! "tasks")))))]
         (is (= "" output))
         (is (false? @discovered))
         (is (str/includes? (str err) "Required executable not found: bb"))))))

(deftest cli-tasks-reports-discovery-errors-and-empty-task-list
  (with-redefs-fn {#'core/executable-available? (constantly true)}
    #(do
       (let [err (java.io.StringWriter.)]
         (with-redefs [jus.tui.tasks/discover (constantly {:status :missing})]
           (let [output (binding [*err* err]
                          (with-out-str
                            (is (= 1 (core/run-cli! "tasks")))))]
             (is (= "" output))
             (is (str/includes? (str err) "No bb.edn (with tasks) was found in:")))))
       (let [err (java.io.StringWriter.)]
         (with-redefs [jus.tui.tasks/discover
                       (constantly {:status :invalid
                                    :path   "/tmp/project/bb.edn"
                                    :error  "Map entry is missing a value"})]
           (let [output (binding [*err* err]
                          (with-out-str
                            (is (= 1 (core/run-cli! "tasks")))))]
             (is (= "" output))
             (is (str/includes? (str err) "Invalid bb.edn:"))
             (is (str/includes? (str err) "/tmp/project/bb.edn"))
             (is (str/includes? (str err) "Map entry is missing a value")))))
       (with-redefs [jus.tui.tasks/discover
                     (constantly {:status :ok
                                  :path   "/tmp/project/bb.edn"
                                  :tasks  []})]
         (let [err    (java.io.StringWriter.)
               output (binding [*err* err]
                        (with-out-str
                          (is (= 0 (core/run-cli! "tasks")))))]
           (is (str/includes? output "No public bb tasks found in:"))
           (is (str/includes? output "/tmp/project/bb.edn"))
           (is (= "" (str err))))))))

(deftest cli-tasks-propagates-picker-exit-code
  (with-redefs-fn {#'core/executable-available? (constantly true)
                   #'jus.tui.tasks/discover (constantly {:status :ok
                                                         :path   "/tmp/project/bb.edn"
                                                         :tasks  [{:name "test"
                                                                   :doc  ""}]})
                   #'jus.tui.tasks/run-picker! (fn [tasks]
                                                 (is (= [{:name "test" :doc ""}] tasks))
                                                 42)}
    #(is (= 42 (core/run-cli! "tasks")))))
