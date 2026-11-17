(ns jus.tui.core
  (:require [charm.program :as program]
            [charm.message :as msg]
            [charm.components.text-input :as text-input]
            [charm.render.screen :as screen]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [jus.tui.animation :as animation]
            [jus.tui.config :as config]
            [jus.tui.content :as content]
            [jus.tui.footer :as footer]
            [jus.tui.data :as data]
            [jus.tui.generator :as generator]
            [jus.tui.menu :as menu]
            [jus.tui.markdown :as markdown]
            [jus.tui.news :as news]
            [jus.tui.news.runtime :as news-runtime]
            [jus.tui.news.ui :as news-ui]
            [jus.tui.projects :as projects]
            [jus.tui.repls :as repls]
            [jus.tui.style :as style :refer [error-prefix]])
  (:import (java.lang ProcessBuilder$Redirect)
           (java.nio.file Files LinkOption)
           (org.jline.utils Signals)))

(def project-created-at "  ✓ Project created at ")

(def project-created "  ✓ Project created")

(def version
  "The current jus release version."
  "0.4.0")

(def bbtl-url "https://github.com/paintparty/bbtl")

(def deps-new-version
  "The git tag of the deps-new coordinate used by project generation."
  (get-in generator/deps-new-coordinate ['io.github.seancorfield/deps-new :git/tag]))

(def open-in-browser-icon (if @style/windows-10? ">" "↗"))

(def open-in-browser-suffix (str " " open-in-browser-icon " "))

(def clear-console-on-launch?
  "Whether to clear the visible console before starting the TUI."
  true)

(def menu-column-gap
  "Number of spaces between columns in menu rows."
  3)

(def resource-single-column-width
  "Terminal width below which community resource menus use the single column
  layout."
  62)

(defn- narrow-resource-menu?
  "True when the terminal is narrow enough that the focused resource's
  description is shown below the list instead of in a second column."
  [state]
  (< (:term-width state) resource-single-column-width))

(defn- main-menu-logo-prefix
  []
  (str (apply str (repeat (:row style/main-menu-logo-position) "\n"))
       (apply str (repeat (:column style/main-menu-logo-position) " "))))

(def main-menu-logo
  (str style/logo
       " "
       (style/italic "jus")))

(def nav-separator (style/secondary " ╱ "))

(def main-menu-logo-with-nav
  (str main-menu-logo nav-separator))

(def header-mode
  "Controls the logo/header shown above normal wizard steps.
   :logo shows the logo line; :hidden starts at the progress line."
  :hidden)

(def progress-style
  "Controls the progress indicator for normal wizard steps.
   :bar uses the full-width progress bar; :stars uses a compact 12-star line."
  :bar)

(def default-licenses
  ["EPL-2.0"
   "EPL-1.0"
   "MIT"
   "Apache-2.0"
   "GPL-3.0-only"
   "BSD-2-Clause"
   "CC0-1.0"])

(def dev-success-sequence? false)
(def dev-opening-sequence? false)

(def main-menu-items*
  {:success-sequence (let [l "Play Success Sequence"]
                       {:label     l
                        :nav-label l})
   :opening-sequence (let [l "Replay Opening Sequence"]
                       {:label     l
                        :nav-label l})
   :wizard           (let [l "New Project Wizard"]
                       {:label     l
                        :nav-label "Project Wizard"})
   :projects         {:label "Command Central" :nav-label "Command Central"}
   :news             {:label "Newsfeed" :nav-label "Newsfeed"}
   :repl             (let [l "Launch Interactive REPL"]
                       {:label     l
                        :nav-label l})
   :resources        (let [l "Explore Community Resources"]
                       {:label     l
                        :nav-label "Community Resources"})
   :about            (let [l "About"]
                       {:label     l
                        :nav-label l})})

(defn main-menu-choices
  ([] (main-menu-choices nil))
  ([state]
   (vec (concat
         (when dev-success-sequence? [:success-sequence])
         (when dev-opening-sequence? [:opening-sequence])
         [:wizard :projects :repl :resources :about]
         (when (seq (get-in state [:news :channels])) [:news])))))

(defn main-menu-items
  ([] (main-menu-items nil))
  ([state]
   (mapv #(if (= % :news)
            (str "Newsfeed (" (reduce + 0 (map count (vals (get-in state [:news :messages])))) ")")
            (get-in main-menu-items* [% :label])) (main-menu-choices state))))

(def rebel-readline-version "0.1.11")

(defn build-licenses-list
  "Return default licenses list."
  [_cfg]
  default-licenses)

;; Logical wizard outline. The two location-pickers
;; (:parent-dir-select and :path-confirm) collapse into a single
;; virtual step, :project-location, whose contents depend on the
;; mode chosen at :project-path.
(def steps
  [:project-template
   :project-name
   :group
   :source-layout
   :developer
   :description
   :license
   :confirm
   :project-path
   :project-location
   :path-confirm-final])

(defn effective-step
  "Map a concrete location picker to its logical wizard step."
  [step]
  (if (#{:parent-dir-select :path-confirm} step)
    :project-location
    step))

(defn resolve-virtual
  "Map :project-location to the concrete step based on :path-mode."
  [step path-mode]
  (if (= step :project-location)
    (if (= path-mode "browse") :path-confirm :parent-dir-select)
    step))

(defn step-label
  "Human-readable label shown above each wizard step."
  [step]
  (case step
    :project-template "Project type (creates a deps.edn project)"
    :project-name  "Project name"
    :group         "Group ID for artifact (e.g., io.github.gbelson or com.hooli)"
    :source-layout "Source namespace layout"
    :developer     "Developer"
    :description   "Description"
    :license       "SPDX license"
    :confirm       "Project details (Enter to confirm)"
    :project-path  "Select location for new project"
    :parent-dir-select "Select path for new project"
    :path-confirm  "Navigate and select path for new project"
    :path-confirm-final "Confirm new project path:"))

(defn- project-name-label [state]
  (if (zero? (:template-idx state))
    "Library name"
    "Project name"))

;;     SSSSSSSSSSSSSSS TTTTTTTTTTTTTTTTTTTTTTT         AAA         TTTTTTTTTTTTTTTTTTTTTTTEEEEEEEEEEEEEEEEEEEEEE
;;   SS:::::::::::::::ST:::::::::::::::::::::T        A:::A        T:::::::::::::::::::::TE::::::::::::::::::::E
;;  S:::::SSSSSS::::::ST:::::::::::::::::::::T       A:::::A       T:::::::::::::::::::::TE::::::::::::::::::::E
;;  S:::::S     SSSSSSST:::::TT:::::::TT:::::T      A:::::::A      T:::::TT:::::::TT:::::TEE::::::EEEEEEEEE::::E
;;  S:::::S            TTTTTT  T:::::T  TTTTTT     A:::::::::A     TTTTTT  T:::::T  TTTTTT  E:::::E       EEEEEE
;;  S:::::S                    T:::::T            A:::::A:::::A            T:::::T          E:::::E             
;;   S::::SSSS                 T:::::T           A:::::A A:::::A           T:::::T          E::::::EEEEEEEEEE   
;;    SS::::::SSSSS            T:::::T          A:::::A   A:::::A          T:::::T          E:::::::::::::::E   
;;      SSS::::::::SS          T:::::T         A:::::A     A:::::A         T:::::T          E:::::::::::::::E   
;;         SSSSSS::::S         T:::::T        A:::::AAAAAAAAA:::::A        T:::::T          E::::::EEEEEEEEEE   
;;              S:::::S        T:::::T       A:::::::::::::::::::::A       T:::::T          E:::::E             
;;              S:::::S        T:::::T      A:::::AAAAAAAAAAAAA:::::A      T:::::T          E:::::E       EEEEEE
;;  SSSSSSS     S:::::S      TT:::::::TT   A:::::A             A:::::A   TT:::::::TT      EE::::::EEEEEEEE:::::E
;;  S::::::SSSSSS:::::S      T:::::::::T  A:::::A               A:::::A  T:::::::::T      E::::::::::::::::::::E
;;  S:::::::::::::::SS       T:::::::::T A:::::A                 A:::::A T:::::::::T      E::::::::::::::::::::E
;;   SSSSSSSSSSSSSSS         TTTTTTTTTTTAAAAAAA                   AAAAAAATTTTTTTTTTT      EEEEEEEEEEEEEEEEEEEEEE

(defn project-wizard-state
  "Returns a fresh Project wizard state from a loaded Global config."
  [global-config]
  {:step          :project-template
   :global-config global-config
   :project-name  (text-input/text-input
                   :prompt "" :placeholder "my-lib"
                   :focused true
                   :text-style        (style/style :bold true)
                   :placeholder-style (style/style :faint true))
   :description   (text-input/text-input
                   :prompt "" :placeholder "A Clojure project"
                   :text-style        (style/style :bold true)
                   :placeholder-style (style/style :faint true))
   :group-name    (text-input/text-input
                   :prompt "" :placeholder "io.github.myname"
                   :text-style        (style/style)
                   :placeholder-style (style/style :faint true))
   :group-idx     0
   :group-entry?  false
   :source-layout-idx 0
   :developer     (text-input/text-input
                   :prompt "" :placeholder "Jane Doe"
                   :text-style        (style/style :bold true)
                   :placeholder-style (style/style :faint true))
   :template-idx  0
   :developer-idx 0
   :license-idx   0
   :max-step-idx  0
   :path-mode     nil
   :nav-path      nil
   :nav-idx       0
   :nav-items     []
   :results       {}
   :generation    nil
   :done?         false
   :confetti      nil
   :term-width    80
   :term-height   24
   :error         nil})

(defn main-menu-state
  "Returns a fresh top-level menu state from a loaded Global config."
  [global-config]
  {:step          :main-menu
   :global-config global-config
   :menu-idx      0
   :news          (news/initial-state (:channels (config/news-settings global-config)))
   :term-width    80
   :term-height   24
   :global-config-exists? true
   :error         nil
   :done?         false})

(defn- flash-step?
  "True when Enter on this step confirms a focused list row, so the row
  flashes before the action. Text entry steps and the summary have no
  focused row and act immediately."
  [state]
  (case (:step state)
    (:main-menu :repl-menu :resources
     :project-template :source-layout :license :project-path
     :parent-dir-select :path-confirm :path-confirm-final)
    true

    :group     (not (:group-entry? state))
    :developer (boolean (seq (config/developers (:global-config state))))
    false))

(defn- menu-screen? [step]
  (boolean (some #{step} [:main-menu :repl-menu :resources])))

(def about-content
  (str "Jus is a TUI app for Clojure dialects.\n\nScaffold new projects, launch REPLs, and explore community resources.\n\nBuilt with [Babashka](https://babashka.org/) + [Charm](https://github.com/TimoKramer/charm.clj) + [cljfmt](https://github.com/weavejester/cljfmt).\n\nThe New Project Wizard dispatches to [deps-new](https://github.com/seancorfield/deps-new) " deps-new-version ".\n\nProject Repo: https://github.com/paintparty/jus\n\nContribute or sponsor the project: https://github.com/sponsors/paintparty\n\nVersion: [" version "](https://github.com/paintparty/jus/releases/tag/v" version ")"))

(defn- resource-items [state]
  (or (peek (:resource-stack state)) data/community-resources))

(defn- active-resource [state]
  (nth (resource-items state) (:menu-idx state) nil))

(defn- resource-menu-label [state]
  (peek (:resource-menu-labels state)))

(defn- leave-menu [state]
  (if (and (= :resources (:step state))
           (> (count (:resource-stack state)) 1))
    (let [parent-idx (or (peek (:resource-selection-stack state)) 0)]
      (-> state
          (update :resource-stack pop)
          (update :resource-labels pop)
          (update :resource-menu-labels #(when (seq %) (pop %)))
          (update :resource-selection-stack #(if (seq %) (pop %) []))
          (assoc :menu-idx parent-idx :error nil)))
    (assoc state :step :main-menu :menu-idx 0 :error nil)))

(defn current-step-index [state]
  (.indexOf steps (effective-step (:step state))))

(defn next-step [state]
  (let [idx (current-step-index state)]
    (when (< idx (dec (count steps)))
      (nth steps (inc idx)))))

(defn prev-step [state]
  (let [idx (current-step-index state)]
    (when (pos? idx)
      (nth steps (dec idx)))))

(declare executable-available?
         build-browse-items
         initial-browse-idx
         output-path
         focused-item-arrow
         open-url!)

(defn advance
  "Move to the next wizard step, focusing text inputs as needed.
   Resolves the virtual :project-location step using :path-mode."
  [state]
  (if-let [ns (next-step state)]
    (let [cwd        (System/getProperty "user.dir")
          start      cwd
          actual-ns  (resolve-virtual ns (:path-mode state))
          new-state  (assoc state :step actual-ns :error nil)
          new-idx    (current-step-index new-state)]
      (cond-> (assoc new-state :max-step-idx (max (:max-step-idx state) new-idx))
        (= actual-ns :project-name)
        (assoc-in [:project-name :placeholder]
                  (if (zero? (:template-idx state)) "my-lib" "my-app"))
        (and (= actual-ns :group) (:group-entry? new-state))
        (update :group-name text-input/focus)
        (and (= actual-ns :developer) (empty? (config/developers (:global-config new-state))))
        (update :developer text-input/focus)
        (= actual-ns :description)       (update :description text-input/focus)
        (= actual-ns :project-path)      (assoc :nav-idx 0)
        (= actual-ns :parent-dir-select) (assoc :parent-dirs-idx 0)
        (= actual-ns :path-confirm-final) (assoc :final-idx 0)
        (and (= actual-ns :path-confirm)
             (not= (:path-mode state) "list"))
        (assoc :nav-path  start
               :nav-idx   (initial-browse-idx start)
               :nav-items (build-browse-items start))))
    state))

(defn go-back
  "Move to the previous wizard step, re-focusing text inputs.
   Resolves the virtual :project-location step using :path-mode."
  [state]
  (if-let [ps (prev-step state)]
    (let [actual-ps (resolve-virtual ps (:path-mode state))]
      (cond-> (assoc state :step actual-ps :error nil)
        (= actual-ps :project-name) (update :project-name text-input/focus)
        (and (= actual-ps :group) (:group-entry? state))
        (update :group-name text-input/focus)
        (and (= actual-ps :developer) (empty? (config/developers (:global-config state))))
        (update :developer text-input/focus)
        (= actual-ps :description)  (update :description text-input/focus)
        (= actual-ps :project-path) (assoc :nav-idx 0)))
    state))

(defn validate-text
  "Returns an error string if s is not a valid project name, else nil."
  [s label]
  (cond
    (str/blank? s)
    (str error-prefix
         label " cannot be blank")

    (not (re-matches #"[a-z][a-z0-9\-\.]*" s))
    (str (style/error (str error-prefix label " requirements:"))
         "\n"
         (style/error "    - Lowercase")
         "\n"
         (style/error "    - Alphanumeric with hyphens")
         "\n"
         (style/error "    - Starts with a letter"))

    :else nil))

(defn validate-group
  "Returns an error string if s is not a lowercase dotted namespace-style
  symbol, else nil."
  [s]
  (when-not (re-matches #"[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+" s)
    (str (style/error (str error-prefix "Group requirements:"))
         "\n"
         (style/error "    - Lowercase dot-separated namespace segments")
         "\n"
         (style/error "    - Each segment starts with a letter")
         "\n"
         (style/error "    - Alphanumeric with hyphens"))))

(defn- selected-group-name
  [state]
  (let [groups (config/groups (:global-config state))]
    (if (empty? groups)
      (when (:group-entry? state)
        (text-input/value (:group-name state)))
      (nth (mapv str groups) (:group-idx state) nil))))

(defn system-default-developer
  "Return the exact default Developer value supplied by pinned deps-new."
  []
  (str/capitalize (or (System/getenv "USER")
                      (System/getProperty "user.name"))))

(defn- system-username
  []
  (or (System/getenv "USER")
      (System/getProperty "user.name")))

(defn collect-results
  "Extract the normalized deps-new request from wizard state."
  [state]
  (let [global-config   (:global-config state)
        developers      (config/developers global-config)
        licenses        (build-licenses-list global-config)
        group-name      (selected-group-name state)
        developer       (if (empty? developers)
                          (text-input/value (:developer state))
                          (str (nth developers (:developer-idx state) "")))
        project-name    (text-input/value (:project-name state))
        description     (text-input/value (:description state))
        project-rooted? (zero? (or (:source-layout-idx state) 0))]
    (cond-> {:template    (if (zero? (:template-idx state)) "lib" "app")
             :name        (if (str/blank? group-name)
                            project-name
                            (str group-name "/" project-name))
             :target-dir  (output-path state)
             :developer   developer
             :description description
             :license/id  (str (nth licenses (:license-idx state) "EPL-2.0"))
             :build       :bb}
      project-rooted? (assoc :top project-name :main "core")
      (str/blank? developer) (dissoc :developer)
      (str/blank? description) (dissoc :description))))

(defn- resolve-path
  "Expand a home-relative path and resolve all other relative paths from cwd."
  [path]
  (let [path (str path)
        home (System/getProperty "user.home")
        path (cond
               (= "~" path) home
               (str/starts-with? path "~/") (str home (subs path 1))
               :else path)]
    (.normalize (.toAbsolutePath (fs/path path)))))

(defn- namespace-path
  [namespace]
  (-> namespace
      (str/replace "-" "_")
      (str/replace "." "/")))

(defn source-layout-items
  "Return source namespace choices with path descriptions for the current identity."
  [state]
  (let [project-name (text-input/value (:project-name state))
        group-name   (selected-group-name state)
        rooted-ns    (str project-name ".core")
        default-ns   (str (or group-name project-name) "." project-name)]
    [{:label "Project-rooted (Recommended)"
      :desc  (str "src/" (namespace-path rooted-ns) ".clj")}
     {:label "Group-derived"
      :desc  (str "src/" (namespace-path default-ns) ".clj")}]))

(defn output-path
  "Absolute path where the project will be created."
  [state]
  (let [pn       (text-input/value (:project-name state))
        nav-path (:nav-path state)]
    (str (fs/path (resolve-path (str (or nav-path (System/getProperty "user.dir"))
                                     "/"
                                     pn))))))

(defn display-directory-name
  "Hide the synthetic Adobe profile suffix from a visible directory label."
  [name]
  (or (second (re-matches
               #"(?i)^(.+?)\s{2,}[A-Z0-9._%+-]+@[A-Z0-9.-]+\s+[A-F0-9]+@AdobeID$"
               name))
      name))

(defn build-browse-items
  "Nav items for the bottom section of the browse picker: ../ then subdirs.
   The top-section confirm row is rendered separately and not part of this list."
  [path]
  (let [p      (fs/path path)
        parent (fs/parent p)
        up     (when parent {:label "../" :type :up :path (str parent)})
        dirs   (try
                 (->> (fs/list-dir path)
                      (filter fs/directory?)
                      (remove #(str/starts-with? (str (fs/file-name %)) "."))
                      (sort-by str)
                      (mapv (fn [d]
                              {:label (str (display-directory-name
                                            (str (fs/file-name d)))
                                           "/")
                               :type  :dir
                               :path  (str d)})))
                 (catch Exception _e nil))]
    (vec (filter some? (concat [up] dirs)))))

(defn initial-browse-idx
  "Initial selection is always the top section (index 0)."
  [_path] 0)

(defn- location-choice-items [global-config]
  (vec (concat
        (when (seq (config/parent-dirs global-config))
          [{:label "Choose from my favorites..." :type :list}])
        [{:label "Browse file tree..." :type :browse}])))

(defn config-creation-cmd
  "Async cmd that creates the offered Global config without blocking the TUI."
  [path config-data]
  (program/cmd
   (fn []
     (try
       (config/create-config! path config-data)
       {:type :config-creation-complete
        :result {:status :created}}
       (catch Exception error
         {:type  :config-creation-complete
          :error error})))))

(def loading-spinner-frames
  (let [logo-with-trailing-space (str style/logo " ")]
    [logo-with-trailing-space
     (style/secondary logo-with-trailing-space)
     "  "
     (style/secondary logo-with-trailing-space)]))

(def shimmer-frames 10)
(def shimmer-pause-frames 20)
(def loading-spinner-frame-ms 200)
(def creating-project-animation-ms 0)

(defn shimmer-message [frame message]
  (let [message     (str message)
        cycle-frame (mod frame (+ shimmer-frames shimmer-pause-frames))
        center      (when (< cycle-frame shimmer-frames)
                      (quot (* cycle-frame (dec (count message)))
                            (dec shimmer-frames)))]
    (apply str
           (map-indexed
            (fn [idx ch]
              (cond
                (= idx center) (style/bold-secondary ch)
                (and center (= 1 (Math/abs (- idx center)))) (style/bold-accent ch)
                :else (style/primary ch)))
            message))))

(defn init
  "Charm.clj init. Returns [initial-state cmd]."
  []
  (let [path (config/global-config-path)
        state (if-not (config/config-exists? path)
                (assoc (main-menu-state {}) :global-config-exists? false)
                (main-menu-state (config/load-config path)))]
    (animation/initialize-main-menu state)))

(def flash-on-enter?
  "Flash the focused menu row (bold, medium purple, bold)
  before acting on Enter."
  true)

(def flash-frame-ms 14)

(def ^:dynamic *skip-flash*
  "Bound true while replaying the Enter that started a flash."
  false)

(defn flash-tick-cmd
  "Async cmd that advances the Enter confirmation flash."
  []
  (program/cmd (fn []
                 (Thread/sleep flash-frame-ms)
                 (msg/key-press "flash-tick"))))

(declare update-fn)

(defn- update-flash
  "Advance the flash on each tick. After the last frame, replay the Enter that
  started it so the menu acts as if it had just been pressed."
  [state msg]
  (let [{:keys [frame] original :msg} (:flash state)
        next-frame (inc frame)]
    (cond
      (msg/key-match? msg "ctrl+c")
      (update-fn (dissoc state :flash) msg)

      (not (and (msg/key-press? msg) (= "flash-tick" (:key msg))))
      [state nil]

      (< next-frame (count style/flash-frame-styles))
      [(assoc-in state [:flash :frame] next-frame) (flash-tick-cmd)]

      :else
      (binding [*skip-flash* true]
        (update-fn (dissoc state :flash) original)))))

(defn generation-tick-cmd
  "Async cmd that advances the generation progress animation."
  []
  (program/cmd (fn []
                 (Thread/sleep loading-spinner-frame-ms)
                 (msg/key-press "generation-tick"))))

(defn generation-completion-cmd
  "Async cmd that waits for captured generator completion."
  [handle started-at]
  (program/cmd
   (fn []
     (let [completion (try
                        {:type   :generation-complete
                         :result (generator/await! handle)}
                        (catch Exception error
                          {:type   :generation-complete
                           :result {:exit-code nil
                                    :out       ""
                                    :err       (.getMessage error)}}))
           elapsed    (- (System/currentTimeMillis) started-at)
           remaining  (max 0 (- creating-project-animation-ms elapsed))]
       (when (pos? remaining)
         (Thread/sleep remaining))
       completion))))

(defn generation-failure-message
  "Formats captured generator failure details for retry at confirmation."
  [{:keys [exit-code out err]}]
  (let [detail (some #(when-not (str/blank? %) (str/trim %)) [err out])]
    (str "Project generation failed"
         (when (some? exit-code) (str " (exit " exit-code ")"))
         (when detail (str ":\n\n  " detail)))))

(defn- offered-config
  [state]
  (let [group-name (selected-group-name state)
        developer  (when (empty? (config/developers (:global-config state)))
                     (text-input/value (:developer state)))
        parent-dir (some-> (get-in state [:results :target-dir])
                           java.io.File.
                           .getParentFile
                           .getAbsolutePath)]
    {:path   (config/global-config-path)
     :config (config/project-config
              {:groups      (if (str/blank? group-name) [] [(symbol group-name)])
               :developers  (if (str/blank? developer) [] [developer])
               :parent-dirs (if parent-dir [parent-dir] [])})}))

(defn- skip-config-offer
  [state]
  (animation/start-closing-animation
   (assoc state
          :config-creation nil
          :config-error nil)))

(defn- cancellation-error-message [action error]
  (str "Unable to " action " project generation: " (.getMessage error)
       "\n\n  Press Ctrl-C to retry."))

(defn- cleanup-cancelled-generation [state]
  (let [target-dir (get-in state [:generation :target-dir])]
    (try
      (generator/cleanup! target-dir)
      [(assoc state
              :generation nil
              :error      nil
              :exit-code  130)
       program/quit-cmd]
      (catch Exception error
        [(assoc state :error (cancellation-error-message "clean" error))
         nil]))))

(defn- cancel-generation [state]
  (let [state  (assoc-in state [:generation :cancelling?] true)
        handle (get-in state [:generation :handle])]
    (try
      (generator/cancel! handle)
      (cleanup-cancelled-generation state)
      (catch Exception error
        [(assoc state :error (cancellation-error-message "cancel" error))
         nil]))))

(defn- target-exists? [target-dir]
  (Files/exists (.toPath (java.io.File. target-dir))
                (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn open-url!
  "Open URL in the user's default browser without blocking the TUI."
  [url]
  (try
    (let [os-name (str/lower-case (System/getProperty "os.name"))
          command (cond
                    (str/includes? os-name "mac") "open"
                    (str/includes? os-name "win") "rundll32"
                    :else "xdg-open")
          args    (if (= "rundll32" command)
                    [command "url.dll,FileProtocolHandler" url]
                    [command url])]
      (.start (ProcessBuilder. ^java.util.List args))
      true)
    (catch Exception _
      false)))

;; UUUUUUUU     UUUUUUUUPPPPPPPPPPPPPPPPP   DDDDDDDDDDDDD                  AAA         TTTTTTTTTTTTTTTTTTTTTTTEEEEEEEEEEEEEEEEEEEEEE
;; U::::::U     U::::::UP::::::::::::::::P  D::::::::::::DDD              A:::A        T:::::::::::::::::::::TE::::::::::::::::::::E
;; U::::::U     U::::::UP::::::PPPPPP:::::P D:::::::::::::::DD           A:::::A       T:::::::::::::::::::::TE::::::::::::::::::::E
;; UU:::::U     U:::::UUPP:::::P     P:::::PDDD:::::DDDDD:::::D         A:::::::A      T:::::TT:::::::TT:::::TEE::::::EEEEEEEEE::::E
;;  U:::::U     U:::::U   P::::P     P:::::P  D:::::D    D:::::D       A:::::::::A     TTTTTT  T:::::T  TTTTTT  E:::::E       EEEEEE
;;  U:::::D     D:::::U   P::::P     P:::::P  D:::::D     D:::::D     A:::::A:::::A            T:::::T          E:::::E             
;;  U:::::D     D:::::U   P::::PPPPPP:::::P   D:::::D     D:::::D    A:::::A A:::::A           T:::::T          E::::::EEEEEEEEEE   
;;  U:::::D     D:::::U   P:::::::::::::PP    D:::::D     D:::::D   A:::::A   A:::::A          T:::::T          E:::::::::::::::E   
;;  U:::::D     D:::::U   P::::PPPPPPPPP      D:::::D     D:::::D  A:::::A     A:::::A         T:::::T          E:::::::::::::::E   
;;  U:::::D     D:::::U   P::::P              D:::::D     D:::::D A:::::AAAAAAAAA:::::A        T:::::T          E::::::EEEEEEEEEE   
;;  U:::::D     D:::::U   P::::P              D:::::D     D:::::DA:::::::::::::::::::::A       T:::::T          E:::::E             
;;  U::::::U   U::::::U   P::::P              D:::::D    D:::::DA:::::AAAAAAAAAAAAA:::::A      T:::::T          E:::::E       EEEEEE
;;  U:::::::UUU:::::::U PP::::::PP          DDD:::::DDDDD:::::DA:::::A             A:::::A   TT:::::::TT      EE::::::EEEEEEEE:::::E
;;   UU:::::::::::::UU  P::::::::P          D:::::::::::::::DDA:::::A               A:::::A  T:::::::::T      E::::::::::::::::::::E
;;     UU:::::::::UU    P::::::::P          D::::::::::::DDD A:::::A                 A:::::A T:::::::::T      E::::::::::::::::::::E
;;       UUUUUUUUU      PPPPPPPPPP          DDDDDDDDDDDDD   AAAAAAA                   AAAAAAATTTTTTTTTTT      EEEEEEEEEEEEEEEEEEEEEE

(defn- repl-install-copy-confirmation-lines
  [label mode command width]
  (let [mode-label (case mode :temporary "temp" :local "local")
        confirmation (style/helper-lines
                      (str "✓ Copied to clipboard: " label " in-1 " mode-label
                           " install command.")
                      width)]
    (concat
     (map-indexed (fn [index line]
                    (if (zero? index)
                      (str/replace-first line "✓ Copied to clipboard"
                                         (style/primary "✓ Copied to clipboard"))
                      line))
                  confirmation)
     [""]
     (style/helper-lines "Open a fresh terminal tab and paste." width)
     [""]
     (style/helper-lines "If clipboard access is blocked, copy this manually:" width)
     [command]
     [""]
     (style/helper-lines "Install in-1 for Bash/Zsh:" width)
     ["source <(curl -sL in-1.cc) in-1"]
     [""]
     (style/helper-lines "Install in-1 for Fish:" width)
     ["curl -sL in-1.cc | source - in-1"]
     [""]
     (style/helper-lines "in-1 info: [https://in-1.cc/](https://in-1.cc/)" width))))

(defn- repl-install-items
  [runtime]
  (let [{:keys [label guide]} (repls/option runtime)
        install-items [{:label (str label " temporary install & launch")
                        :desc "Copy in-1 command to clipboard"
                        :mode :temporary
                        :command (repls/install-snippet runtime :temporary)
                        :helper (str "This will copy a **temporary** install snippet to your clipboard.\n\n"
                                     "This will be a **temporary** install using [in-1](https://in-1.cc/), a tool for installing things quickly and easily, with no prerequisites.")}
                       {:label (str label " local install & launch")
                        :desc "Copy in-1 command to clipboard"
                        :mode :local
                        :command (repls/install-snippet runtime :local)
                        :helper (str "This will copy a **local** install snippet to your clipboard.\n\n"
                                     "This will be a **local** install using [in-1](https://in-1.cc/), a tool for installing things quickly and easily, with no prerequisites.")}]
        guide-and-cancel [{:label (str "View " label " Install Guide") :url guide
                           :desc (str "Official " label " installation info")
                           :helper guide}
                          {:label "Cancel" :desc "Returns to previous REPL dialects menu"
                           :helper "Return to the REPL dialects menu."}]]
    (into (if (repls/in-1-installation-supported? runtime) install-items [])
          guide-and-cancel)))

(defn- return-to-repls
  [state]
  (-> state
      (assoc :step :repl-menu :menu-idx (or (:repl-menu-idx state) 0)
             :repl-installed (repls/installed-ids)
             :error nil :action nil)
      (dissoc :repl-install-copy-mode :repl-executable)))

(defn- copy-to-clipboard-cmd
  [mode text]
  (program/cmd
   (fn []
     (print (screen/copy-to-clipboard text))
     (flush)
     {:type :repl-install-copy-complete :mode mode})))

(defn- update-repl-install
  [state message]
  (let [ctrl-c? (msg/key-match? message "ctrl+c")
        escape? (msg/key-match? message :escape)]
    (cond
      ctrl-c? [(assoc state :exit-code 130 :done? true) program/quit-cmd]
      escape? [(return-to-repls state) nil]
      (= :repl-install-copy-complete (:type message))
      (let [selected-mode (:mode (nth (repl-install-items (:repl-id state))
                                      (:menu-idx state)))]
        [(cond-> state
           (= selected-mode (:mode message))
           (assoc :repl-install-copy-mode (:mode message) :error nil)) nil])
      (msg/key-match? message "enter")
      (let [{:keys [mode url command]}
            (nth (repl-install-items (:repl-id state)) (:menu-idx state))]
        (cond
          mode [state (copy-to-clipboard-cmd mode command)]
          url (if (open-url! url) [state nil]
                  [(assoc state :error
                          (str "Unable to open " url ". Open it manually in your browser.")) nil])
          :else [(return-to-repls state) nil]))
      (or (msg/key-match? message :up) (msg/key-match? message "k"))
      [(-> state
           (update :menu-idx #(max 0 (dec %)))
           (dissoc :repl-install-copy-mode :error)) nil]
      (or (msg/key-match? message :down) (msg/key-match? message "j"))
      [(-> state
           (update :menu-idx #(min (dec (count (repl-install-items (:repl-id state))))
                                   (inc %)))
           (dissoc :repl-install-copy-mode :error)) nil]
      :else [state nil])))

(defn- dashboard-items [state]
  (vec (concat [{:label "Add an existing project" :section :add}]
               (map #(assoc % :section :recent) (get-in state [:catalog :recent]))
               (map #(assoc % :section :other) (get-in state [:catalog :other])))))

(defn- dashboard-default-idx [catalog]
  (if (or (seq (:recent catalog)) (seq (:other catalog))) 1 0))

(def ^:private dashboard-steps
  #{:projects :project-location-choice :project-favorites :project-browse
    :project-add-confirm :project-tasks :project-readme})

(defn- browse-existing-project [state directory mode]
  (let [path (projects/canonical-path directory)]
    (when-not (fs/directory? path)
      (throw (ex-info "Favorite directory is unavailable. Update :parent-dirs in config.edn." {})))
    (assoc state :step :project-browse :nav-path path :browse-existing? true
           :project-location-mode mode
           :nav-items (build-browse-items path) :nav-idx 0 :error nil)))

(defn- dashboard-load [state]
  (let [request (random-uuid)]
    [(assoc state :step :projects :menu-idx 0 :error nil
            :dashboard-request request :dashboard-loading? true)
     (program/cmd
      (fn []
        (try
          (let [{:keys [config error]} (config/load-config-result (config/global-config-path))]
            (when error (throw error))
            {:type :dashboard-loaded :request request :config config
             :catalog (projects/catalog (config/project-paths config)
                                        (System/currentTimeMillis))})
          (catch Exception error
            {:type :dashboard-loaded :request request :error (.getMessage error)}))))]))

(defn- dashboard-back [state]
  (assoc state :step :projects :menu-idx (or (:project-menu-idx state)
                                           (dashboard-default-idx (:catalog state)))
         :error nil :dashboard-busy? false))

(declare render-menu-overflow-row display-user-path)

(defn- project-task-items [state]
  (cond-> [{:id :readme :label "Read the README" :desc "Display the existing README.md"}]
    (not (:project-changelog? state))
    (conj {:id :changelog :label "Create a CHANGELOG" :desc "Create a blank CHANGELOG.md"})))

(defn- readme-page [state]
  (content/page-lines (get-in state [:readme :lines] [""]) (:term-width state)
                      (max 1 (- (:term-height state)
                                (if (or (:readme-rendering? state)
                                        (get-in state [:readme :notice])) 19 18)))
                      (or (:readme-offset state) 0)
                      {:padding-start 4 :dividers? true
                       :render-overflow #(render-menu-overflow-row
                                           (- (:term-width state) 4) %1 %2
                                           (if (= 1 %2) "line" "lines"))}))

(defn- render-readme-command [state]
  (let [request (random-uuid)]
    [(assoc state :readme-request request :readme-rendering? true)
     (program/cmd
      (fn []
        {:type :readme-rendered :request request
         :readme (markdown/render-readme (:readme state) (:term-width state))}))]))

(defn- update-dashboard [state message]
  (let [step (:step state)
        enter? (msg/key-match? message "enter")
        up? (msg/key-match? message :up)
        down? (msg/key-match? message :down)
        escape? (msg/key-match? message :escape)]
    (cond
      (msg/key-match? message "ctrl+c")
      [(assoc state :done? true :exit-code 130) program/quit-cmd]

      (= :dashboard-loaded (:type message))
      (if (= (:request message) (:dashboard-request state))
        [(cond-> (assoc state :dashboard-loading? false :error (:error message))
           (not (:error message)) (assoc :catalog (:catalog message)
                                          :global-config (:config message))
           (and (not (:error message)) (= :projects step))
           (assoc :menu-idx (dashboard-default-idx (:catalog message)))) nil]
        [state nil])

      (= :project-added (:type message))
      (if (= (:request message) (:dashboard-request state))
        (if-let [error (:error message)]
          [(assoc state :dashboard-busy? false :error error) nil]
          (dashboard-load (assoc state :global-config (:config message)
                                 :global-config-exists? true :dashboard-busy? false)))
        [state nil])

      (= :readme-loaded (:type message))
      (if (= (:request message) (:dashboard-request state))
        (let [loaded (cond-> (assoc state :dashboard-busy? false :error (:error message))
                       (:readme message) (assoc :step :project-readme :readme (:readme message)
                                                :readme-offset 0))]
          (if (and (:readme message)
                   (not= (:term-width state) (get-in message [:readme :width])))
            (render-readme-command loaded)
            [loaded nil]))
        [state nil])

      (= :readme-rendered (:type message))
      (if (and (= step :project-readme) (= (:request message) (:readme-request state)))
        [(assoc state :readme (:readme message) :readme-rendering? false) nil]
        [state nil])

      (= :changelog-written (:type message))
      (if (= (:request message) (:dashboard-request state))
        [(cond-> (assoc state :dashboard-busy? false :project-task-error (:error message))
           (:path message) (assoc :project-changelog? true :menu-idx 0
                                  :project-task-message (str "✓ Wrote " (display-user-path (:path message))))) nil]
        [state nil])

      (:dashboard-busy? state) [state nil]

      escape?
      [(case step
         :projects (assoc state :step :main-menu
                          :menu-idx (.indexOf (main-menu-choices) :projects) :error nil)
         :project-add-confirm (assoc state :step :project-browse :nav-idx 0 :error nil)
         :project-favorites (assoc state :step :project-location-choice :menu-idx 0 :error nil)
         :project-browse (assoc state
                                :step (if (= :list (:project-location-mode state))
                                        :project-favorites :project-location-choice)
                                :menu-idx (if (= :list (:project-location-mode state))
                                            (or (:project-favorite-idx state) 0)
                                            (dec (count (location-choice-items (:global-config state)))))
                                :error nil)
         :project-readme (assoc state :step :project-tasks :menu-idx 0 :error nil
                                :readme-request nil :readme-rendering? false)
         (dashboard-back state)) nil]

      (= step :projects)
      (let [items (dashboard-items state)
            selected (nth items (:menu-idx state) nil)]
        (cond
          enter?
          (if (= :add (:section selected))
            [(assoc state :step :project-location-choice :menu-idx 0
                    :project-menu-idx (:menu-idx state) :error nil) nil]
            (if (and selected (not (:missing? selected)))
              [(assoc state :step :project-tasks :selected-project selected
                      :project-changelog? (projects/changelog-exists? (:path selected))
                      :project-task-message nil :project-task-error nil
                      :project-menu-idx (:menu-idx state) :menu-idx 0 :error nil) nil]
              [(assoc state :error "Project directory is unavailable. Update its path in config.edn.") nil]))
          up? [(update state :menu-idx #(max 0 (dec %))) nil]
          down? [(update state :menu-idx #(min (dec (count items)) (inc %))) nil]
          (msg/key-match? message "r")
          (dashboard-load state)
          :else [state nil]))

      (= step :project-location-choice)
      (let [items (location-choice-items (:global-config state))]
        (cond
          enter?
          (if (= :list (:type (nth items (:menu-idx state))))
            [(assoc state :step :project-favorites :menu-idx 0 :error nil) nil]
            (try
              [(browse-existing-project state (str (fs/expand-home "~")) :browse) nil]
              (catch Exception error [(assoc state :error (.getMessage error)) nil])))
          up? [(assoc state :menu-idx 0 :error nil) nil]
          down? [(assoc state :menu-idx (dec (count items)) :error nil) nil]
          :else [state nil]))

      (= step :project-favorites)
      (let [favorites (config/parent-dirs (:global-config state))]
        (cond
          enter?
          (if-let [directory (nth favorites (:menu-idx state) nil)]
            (try
              [(browse-existing-project
                (assoc state :project-favorite-idx (:menu-idx state)) directory :list) nil]
              (catch Exception error [(assoc state :error (.getMessage error)) nil]))
            [state nil])
          up? [(update state :menu-idx #(max 0 (dec %))) nil]
          down? [(update state :menu-idx #(min (max 0 (dec (count favorites))) (inc %))) nil]
          :else [state nil]))

      (= step :project-browse)
      (cond
        enter?
        (if (zero? (:nav-idx state))
          (try
            (let [path (projects/canonical-path (:nav-path state))
                  config-path (str (config/global-config-path))
                  source (config/update-source config-path)]
              [(assoc state :step :project-add-confirm :pending-project path
                      :project-config-path config-path :project-config-source source
                      :menu-idx 0 :error nil) nil])
            (catch Exception error [(assoc state :error (.getMessage error)) nil]))
          (let [path (:path (nth (:nav-items state) (dec (:nav-idx state))))]
            [(assoc state :nav-path path :nav-items (build-browse-items path)
                    :nav-idx 0 :error nil) nil]))
        up? [(update state :nav-idx #(max 0 (dec %))) nil]
        down? [(update state :nav-idx #(min (count (:nav-items state)) (inc %))) nil]
        :else [state nil])

      (= step :project-add-confirm)
      (cond
        (or up? down?) [(update state :menu-idx #(if (zero? %) 1 0)) nil]
        enter?
        (if (= 1 (:menu-idx state))
          [(assoc state :step :project-browse :nav-idx 0 :error nil) nil]
          (let [request (random-uuid)]
            [(assoc state :dashboard-busy? true :dashboard-request request :error nil)
             (program/cmd
              (fn []
                (try
                  {:type :project-added :request request
                   :config (config/add-project! (:project-config-path state)
                                                 (:pending-project state)
                                                 (:project-config-source state))}
                  (catch Exception error
                    {:type :project-added :request request :error (.getMessage error)}))))]))
        :else [state nil])

      (= step :project-tasks)
      (cond
        up? [(update state :menu-idx #(max 0 (dec %))) nil]
        down? [(update state :menu-idx #(min (dec (count (project-task-items state))) (inc %))) nil]
        enter?
        (let [request (random-uuid)]
          [(assoc state :dashboard-busy? true :dashboard-request request :error nil
                  :project-task-error nil :project-task-message nil)
           (program/cmd
            (fn []
              (try
                (if (= :changelog (:id (nth (project-task-items state) (:menu-idx state))))
                  {:type :changelog-written :request request
                   :path (projects/create-changelog! (:selected-project state))}
                  {:type :readme-loaded :request request
                   :readme (markdown/render-readme
                            (projects/readme (get-in state [:selected-project :path]))
                            (:term-width state))})
                (catch Exception error
                  {:type (if (= :changelog (:id (nth (project-task-items state) (:menu-idx state))))
                           :changelog-written :readme-loaded)
                   :request request :error (.getMessage error)}))))])
        :else [state nil])

      (= step :project-readme)
      (let [{:keys [offset max-offset visible-lines]} (readme-page state)
            next-offset (cond
                          (or down? (msg/key-match? message "j")) (inc offset)
                          (or up? (msg/key-match? message "k")) (dec offset)
                          (or (msg/key-match? message :pgdown)
                              (msg/key-match? message :page-down)
                              (msg/key-match? message " ")) (+ offset visible-lines)
                          (or (msg/key-match? message :pgup)
                              (msg/key-match? message :page-up)) (- offset visible-lines)
                          (msg/key-match? message :home) 0
                          (msg/key-match? message :end) max-offset
                          :else offset)]
        [(assoc state :readme-offset (max 0 (min max-offset next-offset))) nil])

      :else [state nil])))

(defn- update-app
  "Charm.clj update. Dispatches on animation state, then wizard step."
  [state msg]
  (cond
    ;; Keep viewport dimensions current during every background phase.
    (msg/window-size? msg)
    (let [resized (animation/resize-state state msg)]
      (if (and (= :project-readme (:step state))
               (not= (:term-width state) (:term-width resized)))
        (render-readme-command resized)
        [resized nil]))

    (dashboard-steps (:step state))
    (update-dashboard state msg)

    (= :repl-install-menu (:step state))
    (update-repl-install state msg)

    ;; Opening inward confetti → header reveal → full main menu.
    (:opening-animation state)
    (animation/update-opening-animation state msg)

    ;; Hold the cleared alternate screen briefly before final exit.
    (:post-confetti-blank-screen-pause? state)
    (cond
      (msg/key-match? msg "ctrl+c")
      [(assoc state :exit-code 130 :done? true) program/quit-cmd]

      (and (msg/key-press? msg)
           (= "post-confetti-blank-screen-pause-done" (:key msg)))
      [(assoc state :done? true)
       program/quit-cmd]

      :else
      [state nil])

    ;; Success pause → then confetti
    (:success-pause state)
    (if (and (msg/key-press? msg)
             (= "success-pause-done" (:key msg)))
      (animation/start-closing-animation state)
      [state nil])

    ;; Confetti animation
    (:confetti state)
    (if (and (msg/key-press? msg)
             (= "confetti-tick" (:key msg)))
      (let [confetti (update (:confetti state) :frame inc)]
        (if-not (animation/confetti-visible? confetti
                                             (:term-width state)
                                             (:term-height state))
          (if (:success-preview? state)
            [(assoc state
                    :step :main-menu
                    :menu-idx 0
                    :success-preview? false
                    :success-pause false
                    :confetti nil
                    :results {}
                    :done? false
                    :error nil)
             nil]
            [(assoc state
                    :confetti nil
                    :post-confetti-blank-screen-pause? true)
             (animation/post-confetti-blank-screen-pause-cmd)])
          [(assoc state :confetti confetti)
           (animation/confetti-tick-cmd (:direction confetti))]))
      [state nil])

    ;; Generator subprocess completion and progress animation
    (:generation state)
    (cond
      (msg/key-match? msg "ctrl+c")
      (cancel-generation state)

      (and (= :generation-complete (:type msg))
           (get-in state [:generation :cancelling?]))
      (cleanup-cancelled-generation state)

      (and (= :generation-complete (:type msg))
           (zero? (get-in msg [:result :exit-code] -1)))
      (let [state (assoc state :generation nil :error nil)]
        (if (false? (:global-config-exists? state))
          [(assoc state
                  :step :config-offer
                  :config-offer (offered-config state)
                  :config-offer-idx 0)
           nil]
          (animation/start-success-pause state)))

      (= :generation-complete (:type msg))
      (let [target-dir    (get-in state [:generation :target-dir])
            cleanup-error (try
                            (generator/cleanup! target-dir)
                            nil
                            (catch Exception error
                              (.getMessage error)))
            error-message (cond-> (generation-failure-message (:result msg))
                            cleanup-error
                            (str "\n\n  Cleanup failed: " cleanup-error))]
        [(assoc state
                :generation nil
                :error      error-message)
         nil])

      (and (msg/key-press? msg)
           (= "generation-tick" (:key msg)))
      [(update-in state [:generation :frame] (fnil inc 0))
       (generation-tick-cmd)]

      :else
      [state nil])

    ;; Wait for the async Global config write to finish.
    (:config-creation state)
    (cond
      (= :config-creation-complete (:type msg))
      (if (:error msg)
        [(assoc state
                :config-creation nil
                :config-error (or (.getMessage ^Exception (:error msg))
                                  "Unable to create Global config."))
         nil]
        (animation/start-success-pause
         (assoc state
                :config-creation nil
                :config-error nil
                :global-config-exists? true)))

      :else [state nil])

    ;; Prompt to persist first-run choices into Global config.
    (= :config-offer (:step state))
    (cond
      (or (msg/key-match? msg :escape)
          (msg/key-match? msg "ctrl+c"))
      (skip-config-offer state)

      (msg/key-match? msg "enter")
      (let [offer (:config-offer state)]
        (if (zero? (or (:config-offer-idx state) 0))
          [(assoc state
                  :config-creation offer
                  :config-error nil)
           (config-creation-cmd (:path offer) (:config offer))]
          (skip-config-offer state)))

      (msg/key-match? msg :up)
      [(assoc state :config-offer-idx 0) nil]

      (msg/key-match? msg :down)
      [(assoc state :config-offer-idx 1) nil]

      :else [state nil])

    (:flash state)
    (update-flash state msg)

    (and flash-on-enter?
         (not *skip-flash*)
         (flash-step? state)
         (msg/key-match? msg "enter"))
    [(assoc state :flash {:frame 0 :msg msg}) (flash-tick-cmd)]

    ;; Shared navigation for main, REPL, and resources menus.
    (menu-screen? (:step state))
    (cond
      (msg/key-match? msg "ctrl+c")
      [(cond-> state
         (= :repl-menu (:step state)) (assoc :exit-code 130))
       program/quit-cmd]

      (msg/key-match? msg :escape)
      (if (= :main-menu (:step state))
        [(assoc state :error "Press Ctrl-C to quit.") nil]
        [(leave-menu state) nil])

      (msg/key-match? msg "enter")
      (case (:step state)
        :main-menu
        (case (nth (main-menu-choices state) (:menu-idx state) nil)
          :success-sequence
          (let [confetti (animation/init-confetti (:term-width state)
                                                  (:term-height state))]
            [(assoc state
                    :success-preview? true
                    :success-pause false
                    :confetti confetti
                    :done? false
                    :error nil)
             (animation/confetti-tick-cmd (:direction confetti))])

          :opening-sequence
          (animation/start-opening-animation state)

          :wizard
          (if (executable-available? "clojure")
            [(assoc (project-wizard-state (:global-config state))
                    :global-config-exists? (get state
                                                :global-config-exists?
                                                true)
                    :term-width (:term-width state)
                    :term-height (:term-height state))
             nil]
            [(assoc state :error (repls/missing-executable-message "clojure"))
             nil])

          :repl [(assoc state :step :repl-menu :menu-idx 0 :error nil
                        :repl-installed (repls/installed-ids)) nil]

          :projects (dashboard-load state)

          :news [(assoc state :step :news-channels :news-index 0
                        :news-menu-idx (:menu-idx state) :error nil) nil]

          :resources
          [(assoc state :step :resources
                  :menu-idx 0
                  :resource-stack [data/community-resources]
                  :resource-labels []
                  :resource-menu-labels []
                  :resource-selection-stack []
                  :error nil) nil]

          :about
          [(assoc state :step :about :error nil) nil]

          [state nil])

        :repl-menu
        (let [runtime (nth (repls/available-options) (:menu-idx state) nil)]
          (if-not runtime
            [state nil]
            (if (and (:installer runtime)
                     (:install-with-in-1? runtime)
                     (repls/installation-supported?))
              (try
                (let [selected (assoc state :repl-id (:id runtime)
                                      :repl-menu-idx (:menu-idx state) :error nil)]
                  (if-let [executable (repls/discover (:id runtime))]
                    [(assoc selected :repl-executable executable :action :repl) program/quit-cmd]
                    [(assoc selected :step :repl-install-menu :menu-idx 0) nil]))
                (catch Exception e
                  [(assoc state :error (.getMessage e)) nil]))
              (if-let [missing (some #(when-not (executable-available? %) %) (:requires runtime))]
                [(assoc state :error (repls/missing-executable-message missing)) nil]
                [(assoc state :repl-id (:id runtime)
                        :repl-menu-idx (:menu-idx state)
                        :action :repl :error nil) program/quit-cmd]))))

        :resources
        (let [{:keys [entries url label menu-label]} (nth (resource-items state)
                                                          (:menu-idx state)
                                                          nil)]
          (cond
            (seq entries)
            [(-> state
                 (update :resource-stack conj entries)
                 (update :resource-labels conj label)
                 (update :resource-menu-labels #(conj (vec (or % [])) menu-label))
                 (update :resource-selection-stack
                         #(conj (vec (or % [])) (:menu-idx state)))
                 (assoc :menu-idx 0 :error nil)) nil]

            (and url (open-url! url)) [state nil]

            :else
            [(assoc state :error
                    (str "Unable to open " url ". Open it manually in your browser.")) nil]))

        [state nil])

      (or (msg/key-match? msg :up)
          (and (= :repl-menu (:step state))
               (msg/key-match? msg "k")))
      [(cond-> (update state :menu-idx #(max 0 (dec (or % 0))))
         (= :main-menu (:step state)) (assoc :error nil)) nil]

      (or (msg/key-match? msg :down)
          (and (= :repl-menu (:step state))
               (msg/key-match? msg "j")))
      (let [limit (case (:step state)
                    :main-menu (dec (count (main-menu-items state)))
                    :repl-menu (dec (count (repls/available-options)))
                    :resources (dec (count (resource-items state)))
                    0)]
        [(cond-> (update state :menu-idx #(min limit (inc (or % 0))))
           (= :main-menu (:step state)) (assoc :error nil)) nil])

      :else
      [state nil])

    ;; Read-only screens return to the main menu without participating in menu navigation.
    (= :about (:step state))
    (cond
      (msg/key-match? msg "ctrl+c") [state program/quit-cmd]
      (msg/key-match? msg :escape) [(assoc state :step :main-menu :menu-idx 0 :error nil) nil]
      :else [state nil])

    ;; Global Ctrl-C exits wizard steps that did not handle it earlier.
    (msg/key-match? msg "ctrl+c")
    [state program/quit-cmd]

    ;; Global Escape backs out of wizard steps or returns to the main menu.
    (msg/key-match? msg :escape)
    (cond
      (and (= :group (:step state))
           (:group-entry? state)
           (empty? (config/groups (:global-config state))))
      [(assoc state :group-entry? false :error nil) nil]

      (= :project-template (:step state))
      [(assoc (main-menu-state (:global-config state))
              :global-config-exists? (get state
                                          :global-config-exists?
                                          true)
              :term-width (:term-width state)
              :term-height (:term-height state))
       nil]

      (prev-step state)
      [(go-back state) nil]

      :else [state program/quit-cmd])

    ;; Normal wizard-step input after global handlers have had first pass.
    :else
    (let [state (assoc state :forward-alert false)]
      (cond
        :else
        (case (:step state)
          :project-template
          (cond
            (msg/key-match? msg "enter") [(advance state) nil]
            (msg/key-match? msg :up)     [(assoc state :template-idx 0) nil]
            (msg/key-match? msg :down)   [(assoc state :template-idx 1) nil]
            :else                        [state nil])

          :project-name
          (cond
            (msg/key-match? msg "enter")
            (let [v   (text-input/value (:project-name state))
                  err (validate-text v "Project name")]
              (if err
                [(assoc state :error err) nil]
                [(advance state) nil]))

            :else
            (let [[inp cmd] (text-input/text-input-update
                             (:project-name state) msg)]
              [(assoc state :project-name inp :error nil) cmd]))

          :group
          (let [groups (config/groups (:global-config state))]
            (cond
              (seq groups)
              (let [last-idx (count groups)]
                (cond
                  (msg/key-match? msg "enter") [(advance state) nil]
                  (msg/key-match? msg :up)
                  [(update state :group-idx #(max 0 (dec %))) nil]
                  (msg/key-match? msg :down)
                  [(update state :group-idx #(min last-idx (inc %))) nil]
                  :else [state nil]))

              (:group-entry? state)
              (cond
                (msg/key-match? msg "enter")
                (let [err (validate-group
                           (text-input/value (:group-name state)))]
                  (if err [(assoc state :error err) nil] [(advance state) nil]))
                :else
                (let [[inp cmd] (text-input/text-input-update
                                 (:group-name state) msg)]
                  [(assoc state :group-name inp :error nil) cmd]))

              :else
              (cond
                (msg/key-match? msg "enter")
                (if (zero? (:group-idx state))
                  [(-> state
                       (assoc :group-entry? true :error nil)
                       (update :group-name text-input/focus))
                   nil]
                  [(advance state) nil])
                (msg/key-match? msg :up)
                [(assoc state :group-idx 0) nil]
                (msg/key-match? msg :down)
                [(assoc state :group-idx 1) nil]
                :else [state nil])))

          :source-layout
          (cond
            (msg/key-match? msg "enter") [(advance state) nil]
            (msg/key-match? msg :up)      [(assoc state :source-layout-idx 0) nil]
            (msg/key-match? msg :down)    [(assoc state :source-layout-idx 1) nil]
            :else                         [state nil])

          :developer
          (let [developers (config/developers (:global-config state))]
            (if (empty? developers)
              (cond
                (msg/key-match? msg "enter")
                [(advance state) nil]
                :else
                (let [[inp cmd] (text-input/text-input-update (:developer state) msg)]
                  [(assoc state :developer inp :error nil) cmd]))
              (let [n (count developers)]
                (cond
                  (msg/key-match? msg "enter") [(advance state) nil]
                  (msg/key-match? msg :up)    [(update state :developer-idx #(max 0 (dec %))) nil]
                  (msg/key-match? msg :down)  [(update state :developer-idx #(min (dec n) (inc %))) nil]
                  :else                        [state nil]))))

          :description
          (cond
            (msg/key-match? msg "enter")
            [(advance state) nil]

            :else
            (let [[inp cmd] (text-input/text-input-update (:description state) msg)]
              [(assoc state :description inp :error nil) cmd]))

          :license
          (let [n (count (build-licenses-list (:global-config state)))]
            (cond
              (msg/key-match? msg "enter") [(advance state) nil]
              (msg/key-match? msg :up)    [(update state :license-idx #(max 0 (dec %))) nil]
              (msg/key-match? msg :down)  [(update state :license-idx #(min (dec n) (inc %))) nil]
              :else                        [state nil]))

          :confirm
          (cond
            (msg/key-match? msg "enter")
            [(advance state) nil]

            :else [state nil])

          :project-path
          (let [items (location-choice-items (:global-config state))]
            (cond
              (msg/key-match? msg "enter")
              (let [selected    (nth items (:nav-idx state) nil)
                    cfg-parents (config/parent-dirs (:global-config state))
                    cwd         (System/getProperty "user.dir")]
                (case (:type selected)
                  :list
                  (if (empty? cfg-parents)
                    [(assoc state
                            :error (str "No :parent-dirs configured under "
                                        "[:tui :projects] in config.edn"
                                        ". Choose Browse instead")) nil]
                    [(-> state
                         (assoc :step      :parent-dir-select
                                :path-mode "list"
                                :error     nil
                                :parent-dirs-idx 0)) nil])

                  :browse
                  (let [start cwd]
                    [(-> state
                         (assoc :step      :path-confirm
                                :path-mode "browse"
                                :error     nil
                                :nav-path  start
                                :nav-idx   (initial-browse-idx start)
                                :nav-items (build-browse-items start))) nil])

                  [state nil]))

              (msg/key-match? msg :up)
              [(assoc state :nav-idx 0 :error nil) nil]

              (msg/key-match? msg :down)
              [(assoc state :nav-idx (min 1 (dec (count items))) :error nil) nil]

              :else [state nil]))

          :parent-dir-select
          (let [parent-dirs (config/parent-dirs (:global-config state))
                n   (count parent-dirs)]
            (cond
              (msg/key-match? msg "enter")
              (let [selected-dir (nth parent-dirs (:parent-dirs-idx state) nil)]
                (if selected-dir
              ;; Skip the file browser; go straight to final confirmation.
                  [(assoc state
                          :step      :path-confirm-final
                          :nav-path  (str (resolve-path selected-dir))
                          :final-idx 0
                          :error     nil) nil]
                  [state nil]))

              (msg/key-match? msg :up)
              [(update state :parent-dirs-idx #(max 0 (dec %))) nil]

              (msg/key-match? msg :down)
              [(update state :parent-dirs-idx #(min (dec n) (inc %))) nil]

              :else [state nil]))

          :path-confirm
      ;; Selection model: idx 0 = top section (confirm), idx 1..n = nav items.
          (let [items   (or (:nav-items state) [])
                sel-idx (:nav-idx state)
                max-idx (count items)]
            (cond
              (msg/key-match? msg "enter")
              (if (zero? sel-idx)
            ;; Top section: confirm chosen location.
                [(advance state) nil]
            ;; Bottom section: navigate into selected dir / parent.
                (let [item     (nth items (dec sel-idx) nil)
                      new-path (:path item)]
                  (if (and new-path (#{:up :dir} (:type item)))
                    [(assoc state
                            :nav-path      new-path
                            :nav-items     (build-browse-items new-path)
                            :nav-idx       (initial-browse-idx new-path)
                            :error         nil) nil]
                    [state nil])))

              (msg/key-match? msg :up)
              (let [new-sel (max 0 (dec sel-idx))]
                [(assoc state
                        :nav-idx new-sel) nil])

              (msg/key-match? msg :down)
              (let [new-sel (min max-idx (inc sel-idx))]
                [(assoc state
                        :nav-idx new-sel) nil])

              :else [state nil]))

          :path-confirm-final
          (let [sel (or (:final-idx state) 0)]
            (cond
              (msg/key-match? msg "enter")
              (case sel
                ;; 0 = confirm: generate the project.
                0 (let [results  (collect-results state)
                        out-path (:target-dir results)]
                    (if (target-exists? out-path)
                      [(assoc state
                              :error
                              (str error-prefix
                                   " Already exists: " (str/replace out-path #"//" "/")
                                   "\n\n"
                                   "  Go back and choose a different project name."
                                   "\n"
                                   "  ~ OR ~"
                                   "\n"
                                   "  Move or delete the existing dir and return to this screen."))
                       nil]
                      (try
                        (let [handle     (generator/start! results)
                              started-at (System/currentTimeMillis)]
                          [(assoc state
                                  :results    results
                                  :generation {:handle     handle
                                               :target-dir out-path
                                               :frame      0
                                               :started-at started-at}
                                  :error      nil)
                           (program/batch
                            (generation-completion-cmd
                             handle
                             started-at)
                            (generation-tick-cmd))])
                        (catch Exception e
                          [(assoc state :error (.getMessage e)) nil]))))
            ;; 1 = pick a different location: jump to step 12.
                1 [(assoc state
                          :step      :project-path
                          :final-idx 0
                          :nav-idx   0
                          :error     nil) nil]
                [state nil])

              (msg/key-match? msg :up)
              [(assoc state :final-idx (max 0 (dec sel)) :error nil) nil]

              (msg/key-match? msg :down)
              [(assoc state :final-idx (min 1 (inc sel)) :error nil) nil]

              :else [state nil]))

          [state nil])))))

(defn update-fn
  "Dispatch feed results globally, retaining the feed when other screens rebuild state."
  [state message]
  (cond
    (= :news-updated (:type message))
    (if (= (:service-id message) (get-in state [:news-service :id]))
      [(news-ui/preserve-position state (assoc state :news (:news message)))
       (news-runtime/next-update-command (:news-service state))]
      [state nil])

    (and (news-ui/steps (:step state)) (not (msg/window-size? message)))
    (news-ui/update-screen state message)

    :else
    (let [[next-state command] (update-app state message)]
      [(merge next-state (select-keys state [:news :news-service])) command])))

;; VVVVVVVV           VVVVVVVVIIIIIIIIIIEEEEEEEEEEEEEEEEEEEEEEWWWWWWWW                           WWWWWWWW
;; V::::::V           V::::::VI::::::::IE::::::::::::::::::::EW::::::W                           W::::::W
;; V::::::V           V::::::VI::::::::IE::::::::::::::::::::EW::::::W                           W::::::W
;; V::::::V           V::::::VII::::::IIEE::::::EEEEEEEEE::::EW::::::W                           W::::::W
;;  V:::::V           V:::::V   I::::I    E:::::E       EEEEEE W:::::W           WWWWW           W:::::W 
;;   V:::::V         V:::::V    I::::I    E:::::E               W:::::W         W:::::W         W:::::W  
;;    V:::::V       V:::::V     I::::I    E::::::EEEEEEEEEE      W:::::W       W:::::::W       W:::::W   
;;     V:::::V     V:::::V      I::::I    E:::::::::::::::E       W:::::W     W:::::::::W     W:::::W    
;;      V:::::V   V:::::V       I::::I    E:::::::::::::::E        W:::::W   W:::::W:::::W   W:::::W     
;;       V:::::V V:::::V        I::::I    E::::::EEEEEEEEEE         W:::::W W:::::W W:::::W W:::::W      
;;        V:::::V:::::V         I::::I    E:::::E                    W:::::W:::::W   W:::::W:::::W       
;;         V:::::::::V          I::::I    E:::::E       EEEEEE        W:::::::::W     W:::::::::W        
;;          V:::::::V         II::::::IIEE::::::EEEEEEEE:::::E         W:::::::W       W:::::::W         
;;           V:::::V          I::::::::IE::::::::::::::::::::E          W:::::W         W:::::W          
;;            V:::V           I::::::::IE::::::::::::::::::::E           W:::W           W:::W           
;;             VVV            IIIIIIIIIIEEEEEEEEEEEEEEEEEEEEEE            WWW             WWW            

(declare strip-ansi)

(defn render-progress
  "Step counter + horizontal bar that stretches to align with the
   right edge of the bordered text-fields and menus.
   Shows current progress and forward history with primary emphasis."
  [state]
  (let [idx     (current-step-index state)
        max-idx (:max-step-idx state)
        total   (count steps)
        tw      (or (:term-width state) 80)
        prefix  (str "Step " (inc idx) "/" total "  ")
        fixed-prefix
        (str "  " main-menu-logo-with-nav
             (style/italic (str (-> main-menu-items* :wizard :nav-label) ": " prefix)))
        bar-w   (max 0 (- (- tw 2) (count (strip-ansi fixed-prefix))))
        segment-widths
        (let [base      (quot bar-w total)
              remainder (mod bar-w total)]
          (vec (concat (repeat (dec total) base)
                       [(+ base remainder)])))
        ;; Current step progress
        curr-w  (reduce + (take (inc idx) segment-widths))
        ;; Historical progress (up to max-idx)
        hist-w  (reduce + (take (inc (max idx max-idx)) segment-widths))
        extra-w (- hist-w curr-w)
        empty-w (- bar-w hist-w)
        full    (apply str (repeat curr-w "━"))
        history (apply str (repeat (max 0 extra-w) "━"))
        empty   (apply str (repeat (max 0 empty-w) "┄"))]
    (str fixed-prefix
         (style/primary full)
         (style/primary history)
         (style/secondary empty))))

(defn render-star-progress
  "Compact step counter with one star per logical wizard step."
  [state]
  (let [idx   (current-step-index state)
        total (count steps)
        stars (->> (range total)
                   (map #(if (<= % idx) "★" "☆"))
                   (str/join " "))]
    (str "  "
         main-menu-logo-with-nav
         (style/italic (str (-> main-menu-items* :wizard :nav-label)
                            ": Step " (inc idx) "/" total))
         "  "
         stars)))

(defn render-step-progress [state]
  (case progress-style
    :stars (render-star-progress state)
    :bar   (render-progress state)
    (render-progress state)))

(defn strip-ansi [s]
  (str/replace s #"\033\[[0-9;]*m" ""))

(defn render-text-field
  "Bordered text input with 1-char side margins and 1-char inner left padding."
  [input term-width]
  (let [content   (text-input/text-input-view input)
        inner-w   (- term-width 4)
        h-bar     (apply str (repeat (max 0 inner-w) "─"))
        vis-len   (count (strip-ansi content))
        padding   (apply str (repeat (max 0 (- inner-w 1 vis-len)) " "))]
    (str " " (style/secondary (str "╭" h-bar "╮")) "\n"
         " " (style/secondary "│") " " content padding (style/secondary "│") "\n"
         " " (style/secondary (str "╰" h-bar "╯")))))

(defn render-outlined-button
  "Three-line button with rounded box-drawing border. Returns [top mid bot]."
  [label _class]
  (let [inner (str "  " label "  ")
        w     (count inner)
        h-bar (apply str (repeat w "─"))]
    [(style/secondary (str "╭" h-bar "╮"))
     (style/secondary (str "│" inner "│"))
     (style/secondary (str "╰" h-bar "╯"))]))

(defn- menu-blank-row [inner-w]
  (str " " (style/secondary "│")
       (apply str (repeat (max 0 inner-w) " "))
       (style/secondary "│")))

(defn- menu-window-rows
  ([window inner-w] (menu-window-rows window inner-w true))
  ([window inner-w padded?]
   (concat (when (and padded? (zero? (:hidden-above window)))
             [(menu-blank-row inner-w)])
           (:lines window)
           (when (and padded? (zero? (:hidden-below window)))
             [(menu-blank-row inner-w)]))))

(defn render-toggle
  "Vertical YES/NO list with full-width bordered box; selected item is emphasized."
  [on? term-width]
  (let [inner-w  (- term-width 4)
        h-bar    (apply str (repeat inner-w "─"))
        top      (str " " (style/secondary (str "╭" h-bar "╮")))
        bot      (str " " (style/secondary (str "╰" h-bar "╯")))
        yes-str  (if on? "  > Yes" "    Yes")
        no-str   (if on? "    No" "  > No")
        yes-pad  (apply str (repeat (max 0 (- inner-w (count yes-str))) " "))
        no-pad   (apply str (repeat (max 0 (- inner-w (count no-str))) " "))
        yes-line (str " "
                      (style/secondary "│")
                      (if on? (style/primary yes-str) yes-str)
                      yes-pad
                      (style/secondary "│"))
        no-line  (str " "
                      (style/secondary "│")
                      (if on? no-str (style/primary no-str))
                      no-pad
                      (style/secondary "│"))]
    (str/join "\n" [top (menu-blank-row inner-w) yes-line no-line
                    (menu-blank-row inner-w) bot])))

(defn item-label [x]
  (cond
    (keyword? x) (str/capitalize (str/replace (name x) "-" " "))
    :else         (str x)
    :else         (str x)))

(defn- render-menu-overflow-row
  ([inner-w arrow hidden] (render-menu-overflow-row inner-w arrow hidden nil))
  ([inner-w arrow hidden unit]
  (let [content (str "  " arrow " " hidden " more" (when unit (str " " unit)))]
    (str " " (style/sgr "2" "│")
         (style/secondary content)
         (apply str (repeat (max 0 (- inner-w (count content))) " "))
         (style/sgr "2" "│")))))

(defn- menu-row-capacity
  ([state] (menu-row-capacity state 0))
  ([state additional-reserved-lines]
   (max 1 (- (or (:term-height state) 24)
             12
             additional-reserved-lines))))

(defn render-list
  "Navigable bordered list; selected item uses primary emphasis.
   Items may be strings/keywords or maps with :label and optional :type."
  [items selected-idx term-width capacity]
  (let [inner-w   (- term-width 4)
        h-bar     (apply str (repeat (max 0 inner-w) "─"))
        top       (str " " (style/secondary (str "╭" h-bar "╮")))
        bot       (str " " (style/secondary (str "╰" h-bar "╯")))
        preview-label-width
        (apply max
               0
               (map #(if (and (map? %) (:preview %))
                       (count (:label %))
                       0)
                    items))
        item-text (fn [x]
                    (if (map? x)
                      (let [{:keys [label preview]} x]
                        (if preview
                          (str (format (str "%-" (+ preview-label-width
                                                    menu-column-gap)
                                            "s")
                                       label)
                               preview)
                          label))
                      (item-label x)))
        window    (menu/render-window
                   items selected-idx capacity (+ capacity 2)
                   (fn [index item]
                     (let [selected? (= index selected-idx)
                           confirm?  (and (map? item) (= :confirm (:type item)))
                           prefix    (if selected? "  > " "    ")
                           text      (str prefix (item-text item))
                           pad       (apply str (repeat (max 0 (- inner-w (count text))) " "))
                           style-fn  (cond selected? style/selected
                                           confirm?   identity
                                           :else      identity)]
                       (str " "
                            (style/secondary "│")
                            (style-fn text)
                            pad
                            (style/secondary "│"))))
                   (partial render-menu-overflow-row inner-w))]
    (str/join "\n" (concat [top] (menu-window-rows window inner-w) [bot]))))

(defn- truncate-with-ellipsis
  [text width]
  (let [text  (str text)
        width (max 0 width)]
    (cond
      (<= (count text) width) text
      (<= width 3)            (subs text 0 width)
      :else                   (str (subs text 0 (- width 3)) "..."))))

(defn- single-column-resource-lines
  "Lines for one item in the single column layout. The description is shown
  only for the focused item, indented below the label and emphasized like it.
  Every item takes exactly two rows (the second is blank when not focused or
  when there is no description) so focus moving never changes the menu height."
  [{:keys [label desc url]} selected? inner-w]
  (let [row       (fn [styled visible-len]
                    (str " " (style/secondary "│")
                         styled
                         (apply str (repeat (max 0 (- inner-w visible-len)) " "))
                         (style/secondary "│")))
        prefix    (if selected? "  > " "    ")
        label-row (let [text (truncate-with-ellipsis (str prefix label) inner-w)]
                    (row (if selected? (style/primary text) text) (count text)))
        suffix    (if (and selected? url) open-in-browser-suffix "")
        indent    "      "
        desc-text (when (and selected? (seq desc))
                    (truncate-with-ellipsis
                     desc
                     (- inner-w (count indent) (count suffix))))
        desc-row  (when desc-text
                    (row (str (style/primary (str indent desc-text)) suffix)
                         (+ (count indent) (count desc-text) (count suffix))))]
    [label-row (or desc-row (row "" 0))]))

(defn render-resource-list
  "Render resource labels and descriptions, without URLs.
  Options:
    :layout  :two-column (default) shows label and description side by side.
             :single-column shows each label on its own row and the
             description of the focused item only, indented below it.
             :desc-footer shows labels only. The caller renders the focused
             description below the list."
  ([items selected-idx term-width capacity]
   (render-resource-list items selected-idx term-width capacity nil))
  ([items selected-idx term-width capacity {:keys [layout]}]
   (let [inner-w       (- term-width 4)
         single-column? (= :single-column layout)
         desc-footer?  (= :desc-footer layout)
         label-w       (apply max 0 (map #(count (:label %)) items))
         h-bar         (apply str (repeat (max 0 inner-w) "─"))
         top           (str " " (style/secondary (str "╭" h-bar "╮")))
         bot           (str " " (style/secondary (str "╰" h-bar "╯")))
         window        (menu/render-window
                        items selected-idx capacity (+ capacity 2)
                        (fn [index {:keys [label desc url] :as item}]
                          (let [selected? (= index selected-idx)]
                            (if single-column?
                              (single-column-resource-lines item selected? inner-w)
                              (let [prefix  (if selected? "  > " "    ")
                                    suffix  (if (and selected? url)
                                              open-in-browser-suffix
                                              "")
                                    desc-w  (max 0
                                                 (- inner-w (count prefix) label-w menu-column-gap
                                                    (if url 3 0)))
                                    desc    (truncate-with-ellipsis (or desc "") desc-w)
                                    content (if desc-footer?
                                              (truncate-with-ellipsis
                                               (str prefix label suffix)
                                               inner-w)
                                              (str prefix
                                                   (format (str "%-" label-w "s") label)
                                                   (apply str (repeat menu-column-gap " "))
                                                   desc
                                                   suffix))
                                    pad     (apply str (repeat (max 0 (- inner-w (count content))) " "))]
                                (str " " (style/secondary "│")
                                     (if selected? (style/selected content) content)
                                     pad (style/secondary "│"))))))
                        (partial render-menu-overflow-row inner-w))]
     (str/join "\n" (concat [top] (menu-window-rows window inner-w) [bot])))))

(def focused-item-arrow
  (style/primary ">"))

(defn- display-user-path
  "Abbreviate paths under the current user's home directory for display."
  [path]
  (let [path (str path)
        home (str (System/getProperty "user.home"))]
    (cond
      (= path home) "~"
      (str/starts-with? path (str home "/"))
      (str "~" (subs path (count home)))
      :else path)))

(defn render-path-tail
  "Render a path with only its final segment in primary emphasis."
  [path]
  (let [path  (str path)
        slash (str/last-index-of path "/")]
    (if (some? slash)
      (str (subs path 0 (inc slash)) (style/selected (subs path (inc slash))))
      (style/selected path))))

(defn render-browse
  "Two-section bordered box for the browse-mode location picker.
   Top section shows the projected output path and IS the confirm row
   (selected when nav-idx = 0). Bottom section is the navigable directory
   list (../ + subdirs); selecting an entry navigates. The directory rows
   follow the focused item within the available terminal height."
  [state]
  (let [tw           (:term-width state)
        inner-w      (- tw 4)
        h-bar        (apply str (repeat (max 0 inner-w) "─"))
        top-bar      (str " " (style/secondary (str "╭" h-bar "╮")))
        mid-bar      (str " " (style/secondary (str "├" h-bar "┤")))
        bot-bar      (str " " (style/secondary (str "╰" h-bar "╯")))
        side         (style/secondary "│")
        nav-path     (:nav-path state)
        pn           (when-not (:browse-existing? state)
                       (text-input/value (:project-name state)))
        items        (or (:nav-items state) [])
        sel-idx      (:nav-idx state)
        top-sel?     (zero? sel-idx)
        capacity     (max 1 (- (or (:term-height state) 24) 16))
        ;; Top row: " > <nav-path>/<project>"  (or "   …" when not selected)
        out-path     (if (:browse-existing? state) nav-path (str nav-path "/" pn))
        prefix       (if top-sel? "  > " "    ")
        plain        (str prefix out-path)
        top-pad      (apply str (repeat (max 0 (- inner-w (count plain))) " "))
        top-row      (str " "
                          side
                          (if top-sel?
                            (str "  " focused-item-arrow " ")
                            "    ")
                          (if top-sel?
                            (render-path-tail out-path)
                            out-path)
                          top-pad
                          side)
        item-window (menu/render-window
                     items (max 0 (dec sel-idx)) capacity (+ capacity 2)
                     (fn [orig-i item]
                       (let [selected? (= (inc orig-i) sel-idx)
                             label     (:label item)
                             plain-row (str (if selected? "  > " "    ") label)
                             pad       (apply str (repeat (max 0 (- inner-w (count plain-row))) " "))
                             row-text  (if selected?
                                         (str "  " (style/selected (str "> " label)))
                                         (str "    " label))]
                         (str " " side row-text pad side)))
                     (partial render-menu-overflow-row inner-w))]
    (str/join "\n" (concat [top-bar (menu-blank-row inner-w) top-row mid-bar]
                           (menu-window-rows item-window inner-w)
                           [bot-bar]))))

(defn render-parent-dirs
  "Bordered list of parent dirs with primary emphasis on the selected path."
  [items selected-idx term-width capacity]
  (let [inner-w (- term-width 4)
        h-bar   (apply str (repeat (max 0 inner-w) "─"))
        top     (str " " (style/secondary (str "╭" h-bar "╮")))
        bot     (str " " (style/secondary (str "╰" h-bar "╯")))
        window
        (menu/render-window
         items selected-idx capacity (+ capacity 2)
         (fn [index item]
           (let [selected? (= index selected-idx)
                 path      (str item)
                 slash     (when-not (str/ends-with? path "/") "/")
                 path+     (str path slash)
                 plain     (str (if selected? "  > " "    ") (if selected? path+ path))
                 pad       (apply str (repeat (max 0 (- inner-w (count plain))) " "))
                 content   (str (if selected? (str "  " focused-item-arrow " ") "    ")
                                (if selected? (style/selected path+) path))]
             (str " "
                  (style/secondary "│")
                  content
                  pad
                  (style/secondary "│"))))
         (partial render-menu-overflow-row inner-w))]
    (str/join "\n" (concat [top] (menu-window-rows window inner-w) [bot]))))

(defn render-summary
  "Confirmation screen. Shows all choices in a bordered box."
  [state]
  (let [r       (collect-results state)
        tw      (:term-width state)
        inner-w (- tw 4)
        h-bar   (apply str (repeat (max 0 inner-w) "─"))
        top     (str " " (style/secondary (str "╭" h-bar "╮")))
        bot     (str " " (style/secondary (str "╰" h-bar "╯")))
        lbl     (fn [s] (style/default s))
        value   (fn [s] (style/primary s))
        template-label {"lib" "Library"
                        "app" "App"}
        row     (fn [label-str value-str]
                  (let [content (str "    " label-str value-str)
                        vis     (count (strip-ansi content))
                        pad     (apply str (repeat (max 0 (- inner-w vis)) " "))]
                    (str " " (style/secondary "│") content pad (style/secondary "│"))))
        rows    [(row (lbl "Project type:    ") (value (template-label (:template r))))
                 (row (lbl "Identity:        ") (value (:name r)))
                 (row (lbl "Developer:       ")
                      (if-let [developer (:developer r)]
                        (value developer)
                        (str (value (system-username))
                             (style/secondary " (system default)"))))
                 (row (lbl "Description:     ") (value (:description r)))
                 (row (lbl "SPDX license:    ") (value (:license/id r)))]]
    (str/join "\n" (concat [top (menu-blank-row inner-w)]
                           rows
                           [(menu-blank-row inner-w) bot]))))

(defn help-bar [step]
  (footer/render (str
       "Enter" (style/secondary (if (= :repl-install-menu step) ": copy,  " ": next,  "))
       "↑↓" (style/secondary ": menus,  ")
       "Esc" (style/secondary ": back,  ")
       "Ctrl-C" (style/secondary ": quit"))))

(defn- helper-text
  ([s] (helper-text s :secondary))
  ([s tone]
   (let [render-line (case tone
                       :normal style/default
                       :secondary style/secondary)]
     (str/join "\n" (mapv render-line (str/split s #"\n"))))))

(def logo
  (style/accent-italic "Blah Project Wizard ★ ☆")
  #_(str (style/accent-italic (str "wij" #_"  🍄  " #_" ★ " #_" ✨ "))
         (style/secondary+italic (str "  >  " "deps project wizard"))))

(defn render-step-header [state]
  (case header-mode
    :hidden (render-step-progress state)
    :logo   (str "  " logo "\n\n" (render-step-progress state))
    (render-step-progress state)))

(defn- fit-repl-text
  [text width]
  (let [text (str text) width (max 0 width)]
    (if (<= (count text) width) text
        (if (< width 2) (subs text 0 width) (str (subs text 0 (dec width)) "…")))))

(def ^:private installed-mark "✓")

(defn- render-repl-rows
  "Bordered REPL list. With `:installed` (a set of ids), renders a Dialect, Host,
  Installed? header row, a divider below it, and a check column."
  [items selected width height separators? & [padded? {:keys [installed]}]]
  (let [inner (max 4 (- width 4))
        capacity (max 1 height)
        label-width (apply max 0 (map #(count (:label %)) items))
        desc-width (apply max 0 (map #(count (or (:desc %) (:description %) "")) items))
        header-row (when installed
                     (let [text (fit-repl-text
                                 (str "    "
                                      (format (str "%-" (max 1 label-width) "s") "Dialect")
                                      "  "
                                      (format (str "%-" (max 1 desc-width) "s") "Host")
                                      "  Installed?")
                                 inner)]
                       (str " " (style/sgr "2" "│")
                            (style/secondary text)
                            (apply str (repeat (max 0 (- inner (count text))) " "))
                            (style/sgr "2" "│"))))
        border (fn [left right] (str " " (style/sgr "2" (str left (apply str (repeat inner "─")) right))))
        window (menu/render-window
                items selected capacity (+ capacity 2)
                (fn [index {:keys [id label desc description url]}]
                  (let [focused? (= index selected)
                        suffix (if (and focused? url) open-in-browser-suffix "")
                        label-room (max 1 (- inner 4 (count suffix)))
                        content (str (if focused? "  > " "    ")
                                     (fit-repl-text
                                      (str (format (str "%-" (max 1 label-width) "s") label)
                                           "  "
                                           (if installed
                                             (str (format (str "%-" (max 1 desc-width) "s")
                                                          (or desc description ""))
                                                  "  "
                                                  (when (contains? installed id) installed-mark))
                                             (or desc description "")))
                                      label-room)
                                     suffix)
                        row (str " " (style/sgr "2" "│")
                                 (if focused? (style/selected content) content)
                                 (apply str (repeat (max 0 (- inner (count content))) " "))
                                 (style/sgr "2" "│"))]
                    (cond-> []
                      (and separators? (#{2 4} index))
                      (conj (str " " (style/sgr "2" "│") (apply str (repeat inner " ")) (style/sgr "2" "│")))
                      true (conj row))))
                (partial render-menu-overflow-row inner))]
    (str/join "\n" (concat [(border "╭" "╮")]
                           (when header-row [header-row (border "├" "┤")])
                           (menu-window-rows window inner (not (false? padded?)))
                           [(border "╰" "╯")]))))

(defn- render-repl-install-screen
  [state]
  (let [width (max 12 (:term-width state))
        height (:term-height state)
        content-width (- width 4)
        label (:label (repls/option (:repl-id state)))
        indent-lines (fn [lines] (str/join "\n" (map #(str "  " %) lines)))
        header (str (main-menu-logo-prefix)
                    main-menu-logo-with-nav
                    (style/italic (-> main-menu-items* :repl :nav-label))
                    nav-separator
                    (style/italic label))
        header (if (<= (count (strip-ansi header)) width)
                 header
                 (str "\n" (style/italic
                            (fit-repl-text (str "  " style/logo " jus ╱ " label) width))))
        helper-slot (fn [lines tone]
                      (str "  "
                           (helper-text (str/join "\n  " lines) tone)))
        shared-footer (let [footer (help-bar :repl-install-menu)]
                        (if (<= (count (strip-ansi (last (str/split-lines footer)))) width)
                          footer
                          (footer/render (style/secondary
                                          (fit-repl-text "Enter · ↑↓ · Esc · Ctrl-C" content-width)))))
        section-gap (if (< height 18) "\n" "\n\n\n")
        items (repl-install-items (:repl-id state))
        selected (:menu-idx state)
        selected-item (nth items selected)
        heading (style/helper-lines (str error-prefix label " installation not found.") content-width)
        unavailable? (not (repls/in-1-installation-supported? (:repl-id state)))
        unavailable-note (when unavailable?
                           (style/helper-lines (str error-prefix
                                                    "Quick install option via in-1 not available for Intel Mac")
                                               content-width))
        copied? (and (:mode selected-item)
                     (= (:mode selected-item) (:repl-install-copy-mode state)))
        command-lines (when copied?
                        (style/helper-lines (:command selected-item) content-width))
        confirmation-lines (when copied?
                             (repl-install-copy-confirmation-lines
                              label (:mode selected-item) (:command selected-item) content-width))
        helper-lines (cond
                       (:error state)
                       (style/helper-lines (:error state) content-width)

                       :else
                       (style/helper-lines (:helper selected-item) content-width))
        shell-lines (if (< height 18) 5 7)
        helper-limit (max 1 (min 8 (- height (count heading) (count unavailable-note) shell-lines 3)))
        command-fits? (and copied? (<= (count command-lines) helper-limit))
        helper (cond
                 (not copied?) (take helper-limit helper-lines)
                 command-fits? (take-last helper-limit helper-lines)
                 :else (take helper-limit
                             (style/helper-lines
                              "Enlarge terminal to view the manual command."
                              content-width)))
        box-budget (max 3 (- height (count heading) (count unavailable-note) (count helper) shell-lines))
        padded? (>= box-budget 5)
        capacity (max 1 (min (count items) (- box-budget (if padded? 4 2))))
        window (menu/visible-window items selected capacity)
        overflow-row-count (count (filter pos? [(:hidden-above window)
                                                (:hidden-below window)]))
        final-helper-limit (max 0 (- (count helper) overflow-row-count))
        helper ((if command-fits? take-last take) final-helper-limit helper)
        helper-tone (if (or (:mode selected-item) (:error state)) :normal :secondary)
        compact-copy? (and copied? (< height 18))
        compact-confirmation-lines (take (max 1 (- height 8)) confirmation-lines)]
    (cond
      compact-copy?
      (str header
           "\n\n"
           (helper-slot compact-confirmation-lines :normal)
           (footer/render (style/secondary "Esc · Ctrl-C"))
           "\n")

      copied?
      (str header
           "\n\n\n"
           (helper-slot confirmation-lines :normal)
           shared-footer
           "\n")

      :else
      (str header
           section-gap
           (indent-lines heading)
           (when unavailable? (str "\n" (indent-lines unavailable-note)))
           "\n"
           (render-repl-rows items selected width capacity false padded?)
           (when (seq helper)
             (str "\n" (helper-slot helper helper-tone)))
           shared-footer
           "\n"))))

(defn render-menu-screen
  "Render one of the top-level menu screens."
  [state]
  (let [step  (:step state)
        title (str (when-not (= step :main-menu)
                     main-menu-logo-with-nav)
                   (case step
                     :main-menu main-menu-logo
                     :repl-menu (-> main-menu-items* :repl :nav-label style/italic)
                     :resources (style/italic
                                 (str/join nav-separator
                                           (mapv style/italic
                                                 (cons (-> main-menu-items*
                                                           :resources
                                                           :nav-label)
                                                       (:resource-labels state)))))))
        items (case step
                :main-menu (main-menu-items state)
                :repl-menu (let [col2-start (->> (repls/available-options)
                                                 (map #(some-> % :label count))
                                                 (apply max)
                                                 (+ 2))]
                             (mapv #(assoc %
                                           :label
                                           (str (:label %)
                                                (str/join
                                                 (repeat (- col2-start
                                                            (or (some-> % :label count)
                                                                0))
                                                         " "))
                                                (:description %)))
                                   (repls/available-options)))
                :resources (resource-items state))
        active-url (:url (active-resource state))
        active-desc (when (and (= step :resources) (narrow-resource-menu? state))
                      (let [d (:desc (active-resource state))]
                        (when (seq d) d)))
        error-message (or (:error state)
                          (when (= step :main-menu)
                            (:error (config/news-settings (:global-config state)))))
        error-lines (if error-message
                      (+ (count (str/split-lines error-message))
                         (if (= step :main-menu) 0 1))
                      0)
        capacity (menu-row-capacity state
                                    (+ (if active-url 1 0) (if active-desc 1 0) error-lines))]
    (str (main-menu-logo-prefix)
         title
         (str "\n\n\n  "
              (case step
                :main-menu "Main menu"
                :repl-menu "Select REPL type"
                :resources
                (or (resource-menu-label state)
                    (if (seq (:resource-labels state))
                      "Select and Enter to visit URL"
                      "Choose a category"))
                "Select option")
              "\n")
         (cond
           (= step :repl-menu)
           (render-repl-rows (repls/available-options) (:menu-idx state)
                             (:term-width state) (max 1 (- (:term-height state) 14)) false true
                             {:installed (or (:repl-installed state) #{})})
           (= step :resources)
           (render-resource-list items (:menu-idx state) (:term-width state)
                                 capacity
                                 {:layout (if (narrow-resource-menu? state)
                                            :desc-footer
                                            :two-column)})
           :else (render-list items (:menu-idx state) (:term-width state)
                              capacity))
         (when active-desc
           (str "\n  " (truncate-with-ellipsis active-desc
                                               (- (:term-width state) 4))))
         (when active-url
           (str "\n  " (style/secondary (str active-url " " open-in-browser-icon))))
         (when error-message
           (str (if (= step :main-menu) "\n  " "\n\n  ")
                (style/error error-message)))
         (help-bar step)
         "\n")))

(defn render-about-screen
  "Render the read-only About page using the reusable reflowing content container."
  [state]
  (str (main-menu-logo-prefix)
       main-menu-logo-with-nav
       (style/italic (-> main-menu-items* :about :nav-label))
       "\n\n\n  About Jus\n"
       (content/render about-content (:term-width state))
       (help-bar :about)
       "\n"))

(defn- render-project-catalog [state]
  (let [items (dashboard-items state)
        inner-w (max 4 (- (:term-width state) 4))
        border (fn [left right]
                 (str " " (style/secondary
                           (str left (apply str (repeat inner-w "─")) right))))
        row (fn [text selected?]
              (let [width (max 1 (- inner-w 8))
                    text (if (> (count (strip-ansi text)) width)
                           (truncate-with-ellipsis (strip-ansi text) width)
                           text)
                    padding (max 0 (- inner-w 4 (count (strip-ansi text))))]
                (str " " (style/secondary "│")
                     (if selected? (str "  " focused-item-arrow " ") "    ") text
                     (apply str (repeat padding " ")) (style/secondary "│"))))
        window (menu/render-window
                items (:menu-idx state) (max 1 (- (:term-height state) 20))
                (max 1 (- (:term-height state) 16))
                (fn [index item]
                  (let [section (:section item)
                        first? (or (zero? index)
                                   (not= section (:section (nth items (dec index)))))
                        selected? (= index (:menu-idx state))
                        label (str (:label item) (when (:missing? item) " (unavailable)"))
                        line (row (if selected? (style/selected label) label) selected?)]
                    (vec (concat
                          (when (and first? (pos? index) (>= (:term-height state) 20))
                            (if (= :add (:section (nth items (dec index))))
                              [(row "" false)]
                              [(row "" false)
                               (row (style/secondary (apply str (repeat (max 0 (- inner-w 8)) "─"))) false)
                               (row "" false)]))
                          [line]))))
                (partial render-menu-overflow-row inner-w))]
    (str/join "\n" (concat [(border "╭" "╮")]
                             (menu-window-rows window inner-w)
                             [(border "╰" "╯")]))))

(defn- render-project-confirmation [state]
  (let [field (fn [[label value]]
                (let [chunks (map #(apply str %) (partition-all (max 1 (- (:term-width state) 14)) value))]
                  (cons (str "  " (format "%-12s" label) (first chunks))
                        (map #(str (apply str (repeat 14 " ")) %) (rest chunks)))))]
    (str (str/join "\n"
                   (mapcat field [["Project:" (:pending-project state)]
                                  ["Config:" (:project-config-path state)]
                                  ["Append to:" "[:tui :projects :paths]"]]))
         "\n" (render-list ["Yes, add project" "Choose a different directory"]
                             (:menu-idx state) (:term-width state) 2))))

(defn- render-dashboard [state]
  (let [step (:step state)
        selected (:selected-project state)
        page (when (= step :project-readme) (readme-page state))]
    (str (main-menu-logo-prefix) main-menu-logo-with-nav
         (style/italic "Command Central")
         (when (#{:project-tasks :project-readme} step)
           (str nav-separator (style/italic (:label selected))))
         "\n\n\n  "
         (case step
           :projects "Select Project"
           :project-location-choice "Select location for existing project"
           :project-favorites "Choose a favorite directory to browse"
           :project-browse "Navigate and select an existing project directory"
           :project-add-confirm "Confirm adding this project"
           :project-tasks (str "Select a task for the project: " (:label selected))
           :project-readme (display-user-path (get-in state [:readme :path])))
         "\n"
         (case step
           :projects (render-project-catalog state)
           :project-location-choice
           (render-list (location-choice-items (:global-config state))
                        (:menu-idx state) (:term-width state) (menu-row-capacity state))
           :project-favorites
           (render-parent-dirs (config/parent-dirs (:global-config state))
                               (:menu-idx state) (:term-width state) (menu-row-capacity state))
           :project-browse (render-browse state)
           :project-add-confirm
           (render-project-confirmation state)
           :project-tasks
           (render-resource-list (project-task-items state)
                                 (:menu-idx state) (:term-width state) (count (project-task-items state)))
           :project-readme (:rendered page))
         (when (= step :projects)
           (str (when-let [path (:path (nth (dashboard-items state) (:menu-idx state) nil))]
                  (str "\n  " (style/secondary
                                  (truncate-with-ellipsis (display-user-path path)
                                                          (max 1 (- (:term-width state) 4))))))
                "\n  " (style/secondary
                          (if (:dashboard-loading? state)
                            "Refreshing projects list..."
                            "R: refresh projects list"))
                (when (some :activity-limited? (concat (get-in state [:catalog :recent])
                                                       (get-in state [:catalog :other])))
                  "\n  Activity scan incomplete for some projects; ordering may be approximate.")))
         (when (and (= step :project-tasks) (:project-task-message state))
           (str "\n  " (style/success (:project-task-message state))))
         (when (and (= step :project-tasks) (:project-task-error state))
           (str "\n  " (style/error "⊗ ERROR: Changelog not written!")
                "\n" (str/join "\n" (map #(str "    " (style/error %))
                                          (style/helper-lines (:project-task-error state)
                                                              (max 1 (- (:term-width state) 4)))))))
         (when (:dashboard-busy? state)
           (str "\n  " (if (= step :project-tasks) "Working..." "Adding project...")))
         (when-let [error (:error state)] (str "\n  " (style/error error)))
         (when (and page (or (:readme-rendering? state) (get-in state [:readme :notice])))
           (str "\n  " (style/secondary
                           (if (:readme-rendering? state)
                             "Reflowing README..."
                             (or (get-in state [:readme :notice]) "")))))
         (footer/render (if page
           (str "↑↓: scroll, Space/PgDn/PgUp: page, Esc: back  "
                (inc (:offset page)) "–" (+ (:offset page) (:visible-lines page))
                "/" (:total-lines page))
           "Enter: next, ↑↓: menus, Esc: back, Ctrl-C: quit"))
         "\n")))

(declare view*)

(defn view
  "Charm.clj view. Renders the current state to a string."
  [state]
  (binding [style/*flash-frame* (get-in state [:flash :frame])]
    (view* state)))

(defn- view*
  [state]
  (cond
    (:opening-animation state)
    (let [{:keys [phase confetti header-frame]} (:opening-animation state)]
      (case phase
        :confetti (animation/render-confetti confetti
                                             (:term-width state)
                                             (:term-height state))
        :header (get animation/opening-header-animation-frames header-frame "")
        ""))

    (:post-confetti-blank-screen-pause? state)
    ""

    (= :repl-install-menu (:step state))
    (render-repl-install-screen state)

    (= :about (:step state))
    (render-about-screen state)

    (news-ui/steps (:step state))
    (news-ui/render state render-list (str (main-menu-logo-prefix) main-menu-logo-with-nav))

    (dashboard-steps (:step state))
    (render-dashboard state)

    (and (menu-screen? (:step state))
         (not (:success-pause state))
         (not (:confetti state)))
    (render-menu-screen state)

    ;; Generator subprocess progress
    (:generation state)
    (let [{:keys [frame]} (:generation state)
          spinner (nth loading-spinner-frames
                       (mod (or frame 0) (count loading-spinner-frames)))]
      (str "\n"
           "  " spinner
           (shimmer-message (or frame 0) "Creating new project...")
           (when-let [error (:error state)]
             (str "\n  " error-prefix (style/primary error)))
           #_(str "\n\n  " (style/secondary "Ctrl-C to cancel") "\n")))

    ;; Global config creation progress
    (:config-creation state)
    "\n  Creating Global config..."

    ;; Success pause (before confetti)
    (:success-pause state)
    (let [r    (:results state)
          path (str/replace (:target-dir r) #"//" "/")]
      (str "\n"
           (style/primary (str project-created-at path))
           "\n\n"
           "    Run `bbtl` from the project root to launch tasks."))

    ;; Confetti animation
    (:confetti state)
    (animation/render-confetti (:confetti state)
                               (:term-width state)
                               (:term-height state))

    ;; Done (after confetti)
    (:done? state)
    (let [r    (:results state)
          path (:target-dir r)]
      (str "\n"
           project-created
           ": "
           (-> path (str/split #"/") last)
           "\n\n"
           "  cd " path "\n\n"
           "  Run bbtl from the project root to select and run tasks (installed separately)."
           (when (= "lib" (:template r))
             (str "\n\n"
                  "  To publish this library to Clojars:\n"
                  "  - set CLOJARS_USERNAME env var\n"
                  "  - set CLOJARS_PASSWORD env var\n"
                  "  - bb ci:deploy"))
           "\n\n"))

    ;; If no config exists, offer to seed one based on values just used
    (= :config-offer (:step state))
    (let [offer       (:config-offer state)
          retry?      (some? (:config-error state))
          choice-idx  (or (:config-offer-idx state) 0)
          choices     [(if retry?
                         "Retry creating a wizard config.edn"
                         "Yes, create a wizard config.edn (Recommended)")
                       "Skip for now"]]
      (str "\n"
           "  " (style/primary "Setup a wizard config to use going forward?")
           "\n\n"
           "  " (str/replace (config/format-config-preview (:config offer))
                             "\n"
                             "\n  ")
           "\n"
           "  This will create `~/.config/jus/config.edn`."
           "\n\n"
           "  Once created, you can manually add more groups, devs, or dirs."
           (when-let [config-error (:config-error state)]
             (str "\n\n  " error-prefix (style/error config-error)))
           "\n\n"
           (render-list choices choice-idx (:term-width state)
                        (menu-row-capacity state))
           (footer/render (style/secondary "Esc or Ctrl-C: skip"))))

    ;; Wizard steps
    :else
    (let [step (:step state)]
      (str "\n"
           (render-step-header state)
           "\n\n\n"
           "  "
           (if (= step :project-name)
             (project-name-label state)
             (step-label step))
           "\n"
           (case step
             :project-template
             (render-list ["Library" "App"]
                          (:template-idx state)
                          (:term-width state)
                          (menu-row-capacity state))

             :project-name
             (render-text-field (:project-name state) (:term-width state))

             :group
             (let [groups (config/groups (:global-config state))
                   items  (if (seq groups)
                            (conj (mapv str groups) "Use project name only")
                            ["Enter a group (Recommended)"
                             "Use project name only"])]
               (str (if (:group-entry? state)
                      (render-text-field (:group-name state)
                                         (:term-width state))
                      (render-list items
                                   (:group-idx state)
                                   (:term-width state)
                                   (menu-row-capacity state 2)))
                    "\n  "
                    (helper-text "Add more groups in ~/.config/jus/config.edn")))

             :source-layout
             (render-resource-list (source-layout-items state)
                                   (:source-layout-idx state)
                                   (:term-width state)
                                   (menu-row-capacity state))

             :developer
             (let [developers (config/developers (:global-config state))]
               (str (if (empty? developers)
                      (render-text-field (:developer state) (:term-width state))
                      (render-list developers
                                   (:developer-idx state)
                                   (:term-width state)
                                   (menu-row-capacity state 2)))
                    "\n  "
                    (helper-text (if (empty? developers)
                                   (str "Leave blank to use the system default: \""
                                        (system-default-developer) "\"")
                                   "Add more developers under :tui :projects in ~/.config/jus/config.edn"))))

             :description
             (str (render-text-field (:description state) (:term-width state))
                  "\n  " (helper-text "Enter to skip description"))

             :license
             (render-list (build-licenses-list (:global-config state))
                          (:license-idx state)
                          (:term-width state)
                          (menu-row-capacity state))

             :confirm
             (render-summary state)

             :project-path
             (let [items (location-choice-items (:global-config state))
                   sel      (:nav-idx state)]
               (render-list items sel (:term-width state)
                            (menu-row-capacity state)))

             :parent-dir-select
             (let [parent-dirs (config/parent-dirs (:global-config state))]
               (if (empty? parent-dirs)
                 (str "\n  "
                      (style/primary "No :parent-dirs configured under :tui :projects")
                      "\n\n  "
                      (helper-text "Press Escape to go back and choose Browse instead."))
                 (str (render-parent-dirs
                       parent-dirs
                       (:parent-dirs-idx state)
                       (:term-width state)
                       (menu-row-capacity state 4))
                      "\n  New project path:\n  "
                      (style/primary
                       (str (let [parent-dir (nth parent-dirs (:parent-dirs-idx state))]
                              (if (str/ends-with? parent-dir "/") parent-dir (str parent-dir "/")))
                            (text-input/value (:project-name state))))
                      "\n\n  "
                      (helper-text "Add additional parent dirs under :tui :projects in config.edn"))))

             :path-confirm
             (render-browse state)

             :path-confirm-final
             (let [tw       (:term-width state)
                   inner-w  (- tw 4)
                   h-bar    (apply str (repeat (max 0 inner-w) "─"))
                   top-bar  (str " " (style/secondary (str "╭" h-bar "╮")))
                   bot-bar  (str " " (style/secondary (str "╰" h-bar "╯")))
                   side     (style/secondary "│")
                   sel      (or (:final-idx state) 0)
                   results  (collect-results state)
                   out-path (display-user-path (:target-dir results))
                   row      (fn [i label]
                              (let [selected? (= i sel)
                                    is-path?  (zero? i)
                                    label     (str/replace label #"//" "/")
                                    plain     (str (if selected? "  > " "    ") label)
                                    pad       (apply str (repeat (max 0 (- inner-w (count plain))) " "))
                                    content   (if selected?
                                                (str "  " focused-item-arrow " "
                                                     (if is-path?
                                                       (render-path-tail label)
                                                       (style/selected label)))
                                                (str "    " label))]
                                (str " " side content pad side)))]
               (str/join "\n" [top-bar
                               (menu-blank-row inner-w)
                               (row 0 out-path)
                               (row 1 "Choose a different location")
                               (menu-blank-row inner-w)
                               bot-bar]))

             "")
           (when-let [err (:error state)]
             ;; ERROR
             (str "\n  " (style/primary err)))
           (if (:forward-alert state)
             (str "\n\n" (style/primary "  ! Press Enter to submit choice and advance to next."))
             "")
           (help-bar step)
           "\n"))))

;; EEEEEEEEEEEEEEEEEEEEEEXXXXXXX       XXXXXXXEEEEEEEEEEEEEEEEEEEEEE       CCCCCCCCCCCCC
;; E::::::::::::::::::::EX:::::X       X:::::XE::::::::::::::::::::E    CCC::::::::::::C
;; E::::::::::::::::::::EX:::::X       X:::::XE::::::::::::::::::::E  CC:::::::::::::::C
;; EE::::::EEEEEEEEE::::EX::::::X     X::::::XEE::::::EEEEEEEEE::::E C:::::CCCCCCCC::::C
;;   E:::::E       EEEEEEXXX:::::X   X:::::XXX  E:::::E       EEEEEEC:::::C       CCCCCC
;;   E:::::E                X:::::X X:::::X     E:::::E            C:::::C              
;;   E::::::EEEEEEEEEE       X:::::X:::::X      E::::::EEEEEEEEEE  C:::::C              
;;   E:::::::::::::::E        X:::::::::X       E:::::::::::::::E  C:::::C              
;;   E:::::::::::::::E        X:::::::::X       E:::::::::::::::E  C:::::C              
;;   E::::::EEEEEEEEEE       X:::::X:::::X      E::::::EEEEEEEEEE  C:::::C              
;;   E:::::E                X:::::X X:::::X     E:::::E            C:::::C              
;;   E:::::E       EEEEEEXXX:::::X   X:::::XXX  E:::::E       EEEEEEC:::::C       CCCCCC
;; EE::::::EEEEEEEE:::::EX::::::X     X::::::XEE::::::EEEEEEEE:::::E C:::::CCCCCCCC::::C
;; E::::::::::::::::::::EX:::::X       X:::::XE::::::::::::::::::::E  CC:::::::::::::::C
;; E::::::::::::::::::::EX:::::X       X:::::XE::::::::::::::::::::E    CCC::::::::::::C
;; EEEEEEEEEEEEEEEEEEEEEEXXXXXXX       XXXXXXXEEEEEEEEEEEEEEEEEEEEEE       CCCCCCCCCCCCC

(defn usage
  []
  (let [logo+link
        (str main-menu-logo
             nav-separator
             "A TUI app for Clojure dialects"
             nav-separator
             "https://github.com/paintparty/jus")]
    (str "Usage:\n"
         "  jus          Launch the TUI\n"
         "  jus version  Print the jus version\n"
         "\n"
         logo+link
         "\n\n")))

(defn- executable-available?
  [executable]
  (try
    (zero?
     (-> (ProcessBuilder. ^java.util.List ["sh" "-c" (str "command -v " executable)])
         (.redirectOutput ProcessBuilder$Redirect/DISCARD)
         (.redirectError ProcessBuilder$Redirect/DISCARD)
         (.start)
         (.waitFor)))
    (catch Exception _
      false)))

(defn- missing-executable-message [executable]
  (repls/missing-executable-message executable))

(defn rebel-readline-command
  "Return a standalone Rebel Readline command.

   The dependency is supplied with -Sdeps for this launch only; no user or
   project deps.edn is modified."
  []
  ["clojure"
   "-J--enable-native-access=ALL-UNNAMED"
   "-Sdeps"
   (pr-str {:deps {'com.bhauman/rebel-readline
                   {:mvn/version rebel-readline-version}}})
   "-M"
   "-m"
   "rebel-readline.main"
   "--color-theme"
   "neutral-screen-theme"])

(defn- run-child-process!
  [command]
  ;; Terminal SIGINT reaches both Jus and the child. Keep the supervisor alive
  ;; while the REPL handles it. Use a caught handler rather than SIG_IGN, which
  ;; an exec'd child would inherit and could no longer handle normally.
  (let [previous-int (Signals/register "INT" (reify Runnable (run [_])))]
    (try
      (-> (ProcessBuilder. ^java.util.List command)
          (.inheritIO)
          (.start)
          (.waitFor))
      (finally
        (Signals/unregister "INT" previous-int)))))

(defn- repl-handoff-classpath []
  (let [resource (io/resource "jus/tui/core.clj")]
    (when-not resource
      (throw (ex-info "Unable to locate jus source root" {})))
    (let [source-root (-> resource .toURI io/file
                          .getParentFile .getParentFile .getParentFile)
          scripts-root (io/file (.getParentFile source-root) "scripts")]
      (str (.getCanonicalPath scripts-root)
           java.io.File/pathSeparator
           (.getCanonicalPath source-root)))))

(defn- run-repl!
  ([] (run-repl! :rebel))
  ([runtime] (run-repl! runtime nil))
  ([runtime executable]
   (try
     (let [command (cond-> ["bb" "-cp" (repl-handoff-classpath) "-m" "repl-handoff.launch"
                            (name runtime)]
                     executable (conj executable))]
       (run-child-process! command))
     (catch Exception exception
       (binding [*out* *err*]
         (println "Unable to start REPL:" (.getMessage exception)))
       1))))

(defn- clear-console!
  "Clear the visible console and move the cursor home without clearing scrollback."
  []
  (print "\033[H\033[2J")
  (flush))

(defn- shell-quote
  "Quote text as one POSIX-shell argument."
  [text]
  (str "'" (str/replace text "'" "'\"'\"'") "'"))

(defn- project-directory-command
  [target-dir]
  (str "cd " (shell-quote target-dir)))

(defn- copy-project-directory-command!
  [target-dir]
  (let [command (project-directory-command target-dir)]
    (print (screen/copy-to-clipboard command))
    (println (str "Copied new project path to clipboard; paste it and press Enter:\n"
                  command))
    (flush)))

(defn- repl-menu-state-after-exit
  [state]
  (-> state
      (assoc :step :repl-menu
             :menu-idx (or (:repl-menu-idx state) 0)
             :repl-installed (repls/installed-ids)
             :done? false
             :exit-code nil
             :error nil)
      (dissoc :action :repl-executable)))

(defn- run-wizard!
  []
  (when clear-console-on-launch?
    (clear-console!))
  (let [path                         (config/global-config-path)
        {:keys [config exists? error location]} (config/load-config-result path)
        _                            (when error
                                       (config/report-config-load-error!
                                        path error location))
        initial-state                 (assoc (main-menu-state config)
                                             :global-config-exists? exists?)]
    (loop [state initial-state
           opening? true]
      (let [channels (:channels (config/news-settings (:global-config state)))
            service (when (seq channels) (news-runtime/start! channels (:news state)))
            state (-> state (assoc :news-service service) (assoc-in [:news :channels] channels))
            final-state (try
                          (program/run {:init       #(let [[initial command]
                                                           (if opening?
                                                             (animation/initialize-main-menu state)
                                                             [state nil])]
                                                       [initial (program/batch command
                                                                 (news-runtime/next-update-command service))])
                                      :update     #'update-fn
                                      :view       #'view
                                      :ctrl-c     :message
                                      :alt-screen true})
                          (finally (news-runtime/stop! service)))]
        (cond
          (= :repl (:action final-state))
          (let [exit-code (if-let [executable (:repl-executable final-state)]
                            (run-repl! (:repl-id final-state) executable)
                            (run-repl! (:repl-id final-state)))]
            (if (#{0 130} exit-code)
              (recur (repl-menu-state-after-exit final-state) false)
              exit-code))

          (:done? final-state)
          (let [exit-code (or (:exit-code final-state) 0)
                target-dir (get-in final-state [:results :target-dir])]
            (when (and (zero? exit-code) target-dir)
              (copy-project-directory-command! target-dir))
            exit-code)

          :else
          (or (:exit-code final-state) 0))))))

(defn run-cli!
  [& args]
  (case (vec args)
    []
    (run-wizard!)

    (["-h"] ["--help"])
    (do
      (print (usage))
      0)

    (["-version"] ["--version"] ["version"])
    (do
      (println "jus" version)
      0)

    (["tasks"] ["t"])
    (do
      (println (str "To list and run bb.edn tasks, install "
                    (style/hyperlink "bbtl" bbtl-url) ":\n"
                    "bbin install io.github.paintparty/bbtl"))
      0)

    (do
      (binding [*out* *err*]
        (print (usage)))
      1)))

(defn -main [& args]
  (let [exit-code (apply run-cli! args)]
    (when-not (zero? exit-code)
      (System/exit exit-code))))
