(ns jus.tui.core
  (:require [charm.program :as program]
            [charm.message :as msg]
            [charm.components.text-input :as text-input]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [jus.tui.animation :as animation]
            [jus.tui.config :as config]
            [jus.tui.content :as content]
            [jus.tui.data :as data]
            [jus.tui.generator :as generator]
            [jus.tui.menu :as menu]
            [jus.tui.repls :as repls]
            [jus.tui.repl-installer :as installer]
            [jus.tui.style :as style :refer [error-prefix]]
            [jus.tui.tasks :as tasks])
  (:import (java.lang ProcessBuilder$Redirect)
           (java.nio.file Files LinkOption)))

(def project-created-at "  ✓ Project created at ")

(def project-created "  ✓ Project created")

(def version
  "The current jus release version."
  "0.2.0")

(def open-in-browser-icon (if @style/windows-10? ">" "↗"))

(def open-in-browser-suffix (str " " open-in-browser-icon " "))

(def clear-console-on-launch?
  "Whether to clear the visible console before starting the TUI."
  true)

(def menu-column-gap
  "Number of spaces between columns in menu rows."
  3)

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
   :repl             (let [l "Launch Interactive REPL"]
                       {:label     l
                        :nav-label l})
   :resources        (let [l "Explore Community Resources"]
                       {:label     l
                        :nav-label "Community Resources"})
   :about            (let [l "About"]
                       {:label     l
                        :nav-label l})})

(defn main-menu-choices []
  (vec (concat
        (when dev-success-sequence? [:success-sequence])
        (when dev-opening-sequence? [:opening-sequence])
        [:wizard :repl :resources :about])))

(defn main-menu-items []
  (mapv #(get-in main-menu-items* [% :label]) (main-menu-choices)))

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
   :term-width    80
   :term-height   24
   :global-config-exists? true
   :error         nil
   :done?         false})

(defn- menu-screen? [step]
  (boolean (some #{step} [:main-menu :repl-menu :resources])))

(def about-content
  "Jus is a TUI app for Clojure dialects.\n\nScaffold new projects, run tasks, launch REPLs, and explore community resources.\n\nBuilt with [Babashka](https://babashka.org/) + [Charm](https://github.com/TimoKramer/charm.clj) + [rewrite-clj](https://github.com/clj-commons/rewrite-clj) + [cljfmt](https://github.com/weavejester/cljfmt).\n\nThe New Project Wizard dispatches to deps-new.\n\nProject Repo: https://github.com/paintparty/jus\n\nContribute or sponsor the project: https://github.com/sponsors/paintparty")

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
  "Return source namespace choices with previews for the current identity."
  [state]
  (let [project-name (text-input/value (:project-name state))
        group-name   (selected-group-name state)
        rooted-ns    (str project-name ".core")
        default-ns   (str (or group-name project-name) "." project-name)]
    [{:label   "Project-rooted (Recommended)"
      :preview (str "src/" (namespace-path rooted-ns) ".clj")}
     {:label   "Group-derived"
      :preview (str "src/" (namespace-path default-ns) ".clj")}]))

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

(defn- repl-install-items
  [runtime]
  (let [{:keys [label installer guide]} (repls/option runtime)
        command (str "source <(curl -fsSL https://in-1.cc) ")
        args (if (= runtime :gloat) " --repl" "")
        install-items [{:label (str "Install " label ", Temporary") :mode :temporary
                        :desc "Installs via in-1 for current session"
                        :command (str command "--temp " installer " && " installer args)
                        :helper (str "This is a temp install using [in-1](https://in-1.cc), a tool for\n"
                                     "installing things quickly and easily, with no prerequisites.")}
                       {:label (str "Install " label ", Persistent") :mode :persistent
                        :desc "Installs via in-1"
                        :command (str command "--local " installer " PREFIX=\"$HOME/.local\" && " installer args)
                        :helper (str "This is a local install using [in-1](https://in-1.cc), a tool for\n"
                                     "installing things quickly and easily, with no prerequisites.\n"
                                     "It will install " label " in `$HOME/.local/bin/" installer "`")}]
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
             :error nil :action nil)
      (dissoc :repl-install :repl-executable)))

(defn- repl-install-tick-cmd
  [operation]
  (program/cmd (fn [] (Thread/sleep loading-spinner-frame-ms)
                 {:type :repl-install-tick :operation operation})))

(defn- begin-repl-install
  [state mode]
  (try
    (let [operation (str (java.util.UUID/randomUUID))
          handle (installer/start! {:runtime (:repl-id state) :mode mode})]
      [(assoc state :step :repl-installing :error nil
              :repl-install {:operation operation :handle handle :frame 0})
       (program/batch
        (program/cmd (fn []
                       {:type :repl-install-complete :operation operation
                        :result (try (installer/await! handle)
                                     (catch Exception e {:status :failed :error (.getMessage e)}))}))
        (repl-install-tick-cmd operation))])
    (catch Exception e
      [(assoc state :step :repl-error :menu-idx 0 :error (.getMessage e)) nil])))

(defn- update-repl-install
  [state message]
  (let [{:keys [operation handle cancel-action]} (:repl-install state)
        ctrl-c? (msg/key-match? message "ctrl+c")
        escape? (msg/key-match? message :escape)]
    (cond
      (= :repl-installing (:step state))
      (cond
        (or ctrl-c? escape?)
        (do (installer/cancel! handle)
            [(assoc-in state [:repl-install :cancel-action]
                       (if (or ctrl-c? (= cancel-action :exit)) :exit :return)) nil])

        (and (= operation (:operation message))
             (= :repl-install-complete (:type message)))
        (let [{:keys [status executable error diagnostics]} (:result message)]
          (cond
            (= cancel-action :exit)
            [(assoc (return-to-repls state) :done? true :exit-code 130) program/quit-cmd]
            (or cancel-action (= status :cancelled)) [(return-to-repls state) nil]
            (= status :installed)
            [(assoc state :repl-install nil :repl-executable executable :action :repl) program/quit-cmd]
            :else
            [(assoc state :step :repl-error :menu-idx 0 :repl-install nil
                    :error (str error (when (seq diagnostics) (str "\n" diagnostics)))) nil]))

        (and (= operation (:operation message)) (= :repl-install-tick (:type message)))
        [(update-in state [:repl-install :frame] inc) (repl-install-tick-cmd operation)]
        :else [state nil])

      ctrl-c? [(assoc state :exit-code 130 :done? true) program/quit-cmd]
      escape? [(return-to-repls state) nil]
      (= :repl-error (:step state))
      (if (msg/key-match? message "enter") [(return-to-repls state) nil] [state nil])
      (msg/key-match? message "enter")
      (let [{:keys [mode url]} (nth (repl-install-items (:repl-id state)) (:menu-idx state))]
        (cond mode (begin-repl-install state mode)
              url (if (open-url! url) [state nil]
                      [(assoc state :step :repl-error :menu-idx 0
                              :error (str "Unable to open " url ". Open it manually in your browser.")) nil])
              :else [(return-to-repls state) nil]))
      (or (msg/key-match? message :up) (msg/key-match? message "k"))
      [(update state :menu-idx #(max 0 (dec %))) nil]
      (or (msg/key-match? message :down) (msg/key-match? message "j"))
      [(update state :menu-idx #(min (dec (count (repl-install-items (:repl-id state))))
                                     (inc %))) nil]
      :else [state nil])))

(defn update-fn
  "Charm.clj update. Dispatches on animation state, then wizard step."
  [state msg]
  (cond
    ;; Keep viewport dimensions current during every background phase.
    (msg/window-size? msg)
    [(animation/resize-state state msg) nil]

    (#{:repl-install-menu :repl-installing :repl-error} (:step state))
    (update-repl-install state msg)

    (#{:repl-install-complete :repl-install-tick} (:type msg))
    [state nil]

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
        (case (nth (main-menu-choices) (:menu-idx state) nil)
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
          [(assoc (project-wizard-state (:global-config state))
                  :global-config-exists? (get state
                                              :global-config-exists?
                                              true)
                  :term-width (:term-width state)
                  :term-height (:term-height state))
           nil]

          :repl [(assoc state :step :repl-menu :menu-idx 0 :error nil) nil]

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
            (if (and (:installer runtime) (repls/installation-supported?))
              (try
                (let [selected (assoc state :repl-id (:id runtime)
                                      :repl-menu-idx (:menu-idx state) :error nil)]
                  (if-let [executable (repls/discover (:id runtime))]
                    [(assoc selected :repl-executable executable :action :repl) program/quit-cmd]
                    [(assoc selected :step :repl-install-menu :menu-idx 0) nil]))
                (catch Exception e
                  [(assoc state :step :repl-error :repl-menu-idx (:menu-idx state)
                          :menu-idx 0 :error (.getMessage e)) nil]))
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
            [(assoc state :error (str "Unable to open " url)
                    ". Open it manually in your browser.") nil]))

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
                    :main-menu (dec (count (main-menu-items)))
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
          (let [has-cfg? (seq (config/parent-dirs (:global-config state)))
                items    (if has-cfg?
                           [{:label "Choose from my favorites..." :type :list}
                            {:label "Browse file tree..." :type :browse}]
                           [{:label "Browse file tree..." :type :browse}])]
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

(defn render-toggle
  "Vertical YES/NO list with full-width bordered box; selected item is emphasized."
  [on? term-width]
  (let [inner-w  (- term-width 4)
        h-bar    (apply str (repeat inner-w "─"))
        top      (str " " (style/secondary (str "╭" h-bar "╮")))
        bot      (str " " (style/secondary (str "╰" h-bar "╯")))
        yes-str  (if on? " > Yes" "   Yes")
        no-str   (if on? "   No" " > No")
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
    (str top "\n" yes-line "\n" no-line "\n" bot)))

(defn item-label [x]
  (cond
    (keyword? x) (str/capitalize (str/replace (name x) "-" " "))
    :else         (str x)
    :else         (str x)))

(defn- render-menu-overflow-row
  [inner-w arrow hidden]
  (let [content (str " " arrow " " hidden " more")]
    (str " " (style/sgr "2" "│")
         (style/secondary content)
         (apply str (repeat (max 0 (- inner-w (count content))) " "))
         (style/sgr "2" "│"))))

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
        rows      (:lines
                   (menu/render-window
                    items selected-idx capacity (+ capacity 2)
                    (fn [index item]
                      (let [selected? (= index selected-idx)
                            confirm?  (and (map? item) (= :confirm (:type item)))
                            prefix    (if selected? " > " "   ")
                            text      (str prefix (item-text item))
                            pad       (apply str (repeat (max 0 (- inner-w (count text))) " "))
                            style-fn  (cond selected? style/primary
                                            confirm?   identity
                                            :else      identity)]
                        (str " "
                             (style/secondary "│")
                             (style-fn text)
                             pad
                             (style/secondary "│"))))
                    (partial render-menu-overflow-row inner-w)))]
    (str/join "\n" (concat [top] rows [bot]))))

(defn render-resource-list
  "Render resource labels and descriptions in two columns, without URLs."
  [items selected-idx term-width capacity]
  (let [inner-w  (- term-width 4)
        label-w  (apply max 0 (map #(count (:label %)) items))
        h-bar    (apply str (repeat (max 0 inner-w) "─"))
        top      (str " " (style/secondary (str "╭" h-bar "╮")))
        bot      (str " " (style/secondary (str "╰" h-bar "╯")))
        rows     (:lines
                  (menu/render-window
                   items selected-idx capacity (+ capacity 2)
                   (fn [index {:keys [label desc url]}]
                     (let [selected? (= index selected-idx)
                           prefix    (if selected? " > " "   ")
                           suffix    (if (and selected? url)
                                       open-in-browser-suffix
                                       "")
                           desc-w    (max 0
                                          (- inner-w (count prefix) label-w menu-column-gap
                                             (if url 3 0)))
                           desc      (let [description (str (or desc ""))]
                                       (cond
                                         (<= (count description) desc-w) description
                                         (<= desc-w 3) (subs description 0 desc-w)
                                         :else (str (subs description 0 (- desc-w 3)) "...")))
                           content   (str prefix
                                          (format (str "%-" label-w "s") label)
                                          (apply str (repeat menu-column-gap " "))
                                          desc
                                          suffix)
                           pad       (apply str (repeat (max 0 (- inner-w (count content))) " "))]
                       (str " " (style/secondary "│")
                            (if selected? (style/primary content) content)
                            pad (style/secondary "│"))))
                   (partial render-menu-overflow-row inner-w)))]
    (str/join "\n" (concat [top] rows [bot]))))

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
      (str (subs path 0 (inc slash)) (style/primary (subs path (inc slash))))
      (style/primary path))))

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
        pn           (text-input/value (:project-name state))
        items        (or (:nav-items state) [])
        sel-idx      (:nav-idx state)
        top-sel?     (zero? sel-idx)
        capacity     (max 1 (- (or (:term-height state) 24) 14))
        ;; Top row: " > <nav-path>/<project>"  (or "   …" when not selected)
        out-path     (str nav-path "/" pn)
        prefix       (if top-sel? " > " "   ")
        plain        (str prefix out-path)
        top-pad      (apply str (repeat (max 0 (- inner-w (count plain))) " "))
        top-row      (str " "
                          side
                          (if top-sel?
                            (str " " focused-item-arrow " ")
                            "   ")
                          (if top-sel?
                            (render-path-tail out-path)
                            out-path)
                          top-pad
                          side)
        item-rows (:lines
                   (menu/render-window
                    items (max 0 (dec sel-idx)) capacity (+ capacity 2)
                    (fn [orig-i item]
                      (let [selected? (= (inc orig-i) sel-idx)
                            label     (:label item)
                            plain-row (str (if selected? " > " "   ") label)
                            pad       (apply str (repeat (max 0 (- inner-w (count plain-row))) " "))
                            row-text  (if selected?
                                        (str " " (style/primary (str "> " label)))
                                        (str "   " label))]
                        (str " " side row-text pad side)))
                    (partial render-menu-overflow-row inner-w)))]
    (str/join "\n" (concat [top-bar top-row mid-bar]
                           item-rows
                           [bot-bar]))))

(defn render-parent-dirs
  "Bordered list of parent dirs; the selected row appends
    the project name with primary emphasis to preview the final path."
  [items selected-idx project-name term-width capacity]
  (let [inner-w (- term-width 4)
        h-bar   (apply str (repeat (max 0 inner-w) "─"))
        top     (str " " (style/secondary (str "╭" h-bar "╮")))
        bot     (str " " (style/secondary (str "╰" h-bar "╯")))
        rows
        (:lines
         (menu/render-window
          items selected-idx capacity (+ capacity 2)
          (fn [index item]
            (let [selected? (= index selected-idx)
                  path      (str item)
                  suffix    (when selected? project-name)
                  slash     (when-not (str/ends-with? path "/") "/")
                  path+     (str path slash)
                  plain     (str (if selected? " > " "   ") (if selected? path+ path) (or suffix ""))
                  pad       (apply str (repeat (max 0 (- inner-w (count plain))) " "))
                  content   (str (if selected? (str " " focused-item-arrow " ") "   ")
                                 (if selected? path+ path)
                                 (when suffix (style/primary suffix)))]
              (str " "
                   (style/secondary "│")
                   content
                   pad
                   (style/secondary "│"))))
          (partial render-menu-overflow-row inner-w)))]
    (str/join "\n" (concat [top] rows [bot]))))

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
                  (let [content (str " " label-str value-str)
                        vis     (count (strip-ansi content))
                        pad     (apply str (repeat (max 0 (- inner-w vis)) " "))]
                    (str " " (style/secondary "│") content pad (style/secondary "│"))))
        rows    [(row (lbl "Type:            ") (value (template-label (:template r))))
                 (row (lbl "Identity:        ") (value (:name r)))
                 (row (lbl "Developer:       ")
                      (if-let [developer (:developer r)]
                        (value developer)
                        (str (value (system-username))
                             (style/secondary " (system default)"))))
                 (row (lbl "Description:     ") (value (:description r)))
                 (row (lbl "SPDX license:    ") (value (:license/id r)))]]
    (str/join "\n" (concat [top] rows [bot]))))

(defn help-bar [_step]
  (str "\n\n  "
       "Enter" (style/secondary ": next,  ")
       "↑↓" (style/secondary ": menus,  ")
       "Esc" (style/secondary ": back,  ")
       "Ctrl-C" (style/secondary ": quit")))

(defn- helper-text [s]
  (str/join "\n" (mapv style/secondary (str/split s #"\n"))))

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

(defn- render-repl-rows
  [items selected width height separators?]
  (let [inner (max 4 (- width 4))
        capacity (max 1 height)
        label-width (apply max 0 (map #(count (:label %)) items))
        border (fn [left right] (str " " (style/sgr "2" (str left (apply str (repeat inner "─")) right))))
        rows (:lines
              (menu/render-window
               items selected capacity (+ capacity 2)
               (fn [index {:keys [label desc description url]}]
                 (let [focused? (= index selected)
                       suffix (if (and focused? url) open-in-browser-suffix "")
                       label-room (max 1 (- inner 3 (count suffix)))
                       content (str (if focused? " > " "   ")
                                    (fit-repl-text
                                     (str (format (str "%-" (max 1 label-width) "s") label)
                                          "  " (or desc description "")) label-room)
                                    suffix)
                       row (str " " (style/sgr "2" "│")
                                (if focused? (style/primary content) content)
                                (apply str (repeat (max 0 (- inner (count content))) " "))
                                (style/sgr "2" "│"))]
                   (cond-> []
                     (and separators? (#{2 4} index))
                     (conj (str " " (style/sgr "2" "│") (apply str (repeat inner " ")) (style/sgr "2" "│")))
                     true (conj row))))
               (partial render-menu-overflow-row inner)))]
    (str/join "\n" (concat [(border "╭" "╮")] rows [(border "╰" "╯")]))))

(defn- render-repl-install-screen
  [state]
  (let [width (max 12 (:term-width state))
        height (:term-height state)
        content-width (- width 4)
        label (:label (repls/option (:repl-id state)))
        step (:step state)
        indent-lines (fn [lines] (str/join "\n" (map #(str "  " %) lines)))
        header (str (main-menu-logo-prefix)
                    main-menu-logo-with-nav
                    (style/italic (-> main-menu-items* :repl :nav-label))
                    nav-separator
                    (style/italic label))
        header (if (<= (count (strip-ansi header)) width)
                 header
                 (str "\n" (style/italic
                            (fit-repl-text (str "  ◒ jus ╱ " label) width))))
        helper-slot (fn [lines]
                      (str "  "
                           (helper-text (str/join "\n  " lines))))
        shared-footer (let [footer (help-bar step)]
                        (if (<= (count (strip-ansi (last (str/split-lines footer)))) width)
                          footer
                          (str "\n\n  "
                               (style/secondary
                                (fit-repl-text "Enter · ↑↓ · Esc · Ctrl-C" content-width)))))
        section-gap (if (< height 18) "\n" "\n\n\n")]
    (case step
      :repl-installing
      (let [{:keys [frame cancel-action]} (:repl-install state)
            spinner (nth loading-spinner-frames
                         (mod (or frame 0) (count loading-spinner-frames)))
            message (if cancel-action "Cancelling installation…" (str "Installing " label "…"))]
        (str header
             section-gap
             "  " spinner (fit-repl-text message (max 1 (- content-width 2)))
             shared-footer
             "\n"))
      :repl-error
      (let [lines (mapcat #(style/helper-lines % content-width)
                          (str/split-lines (installer/clean-diagnostics (:error state))))
            limit (max 1 (- height 9))
            lines (if (> (count lines) limit)
                    (concat [(fit-repl-text "… earlier output omitted …" content-width)]
                            (take-last (max 0 (dec limit)) lines)) lines)]
        (str header
             section-gap
             "  ! Error\n"
             (indent-lines lines)
             "\n"
             (render-repl-rows [{:label "Return to previous REPL dialects menu"}] 0 width 1 false)
             shared-footer
             "\n"))
      :repl-install-menu
      (let [items (repl-install-items (:repl-id state))
            selected (:menu-idx state)
            selected-item (nth items selected)
            heading (style/helper-lines (str error-prefix label " installation not found.") content-width)
            unavailable? (not (repls/in-1-installation-supported? (:repl-id state)))
            unavailable-note (when unavailable?
                               (style/helper-lines (str error-prefix
                                                        "Quick install option via in-1 not available for Intel Mac")
                                                   content-width))
            explanation (style/helper-lines (:helper selected-item) content-width)
            helper (if-let [command (:command selected-item)]
                     (concat ["This will run:"]
                             (style/helper-lines command content-width)
                             [""]
                             explanation)
                     explanation)
            shell-lines (if (< height 18) 4 6)
            helper (take (max 1 (min 8 (- height (count heading) (count unavailable-note) shell-lines 3))) helper)
            box-budget (max 3 (- height (count heading) (count unavailable-note) (count helper) shell-lines))
            capacity (max 1 (min (count items) (- box-budget 2)))
            window (menu/visible-window items selected capacity)
            overflow-row-count (count (filter pos? [(:hidden-above window)
                                                    (:hidden-below window)]))
            helper (take (max 0 (- (count helper) overflow-row-count)) helper)]
        (str header
             section-gap
             (indent-lines heading)
             (when unavailable? (str "\n" (indent-lines unavailable-note)))
             "\n"
             (render-repl-rows items selected width capacity false)
             "\n"
             (helper-slot helper)
             shared-footer
             "\n")))))

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
                :main-menu (main-menu-items)
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
        error-message (:error state)
        error-lines (if error-message
                      (+ (count (str/split-lines error-message))
                         (if (= step :main-menu) 0 1))
                      0)
        capacity (menu-row-capacity state
                                    (+ (if active-url 1 0) error-lines))]
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
                             (:term-width state) (max 1 (- (:term-height state) 12)) false)
           (= step :resources)
           (render-resource-list items (:menu-idx state) (:term-width state)
                                 capacity)
           :else (render-list items (:menu-idx state) (:term-width state)
                              capacity))
         (when active-url
           (str "\n  " (style/secondary active-url)))
         (when error-message
           (str (if (= step :main-menu) "\n  " "\n\n  ")
                (style/error error-message)))
         "\n  "
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
       "\n"
       (help-bar :about)
       "\n"))

(defn view
  "Charm.clj view. Renders the current state to a string."
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

    (#{:repl-install-menu :repl-installing :repl-error} (:step state))
    (render-repl-install-screen state)

    (= :about (:step state))
    (render-about-screen state)

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
           "    Run " (style/primary "jus tasks") " from project root to select and run tasks."))

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
           "  To pick and run a task:\n"
           "  jus tasks"
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
           "\n\n  " (style/secondary "Esc or Ctrl-C: skip")))

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
             (render-list (source-layout-items state)
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
             (let [has-cfg? (seq (config/parent-dirs (:global-config state)))
                   items    (if has-cfg?
                              [{:label "Choose from my favorites..."
                                :type  :list}
                               {:label "Browse file tree..."
                                :type  :browse}]
                              [{:label "Browse file tree..."
                                :type  :browse}])
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
                       (text-input/value (:project-name state))
                       (:term-width state)
                       (menu-row-capacity state 2))
                      "\n  "
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
                                    plain     (str (if selected? " > " "   ") label)
                                    pad       (apply str (repeat (max 0 (- inner-w (count plain))) " "))
                                    content   (if selected?
                                                (str " " focused-item-arrow " "
                                                     (if is-path?
                                                       (render-path-tail label)
                                                       (style/primary label)))
                                                (str "   " label))]
                                (str " " side content pad side)))]
               (str/join "\n" [top-bar
                               (row 0 out-path)
                               (row 1 "Choose a different location")
                               bot-bar]))

             "")
           (when-let [err (:error state)]
             ;; ERROR
             (str "\n  " (style/primary err)))
           (if (:forward-alert state)
             (str "\n\n" (style/primary "  ! Press Enter to submit choice and advance to next."))
             "")
           "\n  "
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
         "  jus tasks    Pick and run a public bb task from ./bb.edn\n"
         "  jus t        Shorthand for `jus tasks`\n"
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

(defn- preflight!
  [executables]
  (if-let [missing (some #(when-not (executable-available? %) %) executables)]
    (do
      (binding [*out* *err*]
        (print (missing-executable-message missing)))
      false)
    true))

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
  (-> (ProcessBuilder. ^java.util.List command)
      (.inheritIO)
      (.start)
      (.waitFor)))

(defn- repl-handoff-classpath []
  (let [resource (io/resource "jus/tui/core.clj")]
    (when-not resource
      (throw (ex-info "Unable to locate jus source root" {})))
    (-> resource
        .toURI
        io/file
        .getParentFile
        .getParentFile
        .getParentFile
        .getParentFile
        (io/file "scripts")
        .getCanonicalPath)))

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

(defn- repl-menu-state-after-exit
  [state]
  (-> state
      (assoc :step :repl-menu
             :menu-idx (or (:repl-menu-idx state) 0)
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
      (let [final-state (program/run {:init       #(if opening?
                                                     (animation/initialize-main-menu state)
                                                     [state nil])
                                      :update     #'update-fn
                                      :view       #'view
                                      :alt-screen true})]
        (cond
          (= :repl (:action final-state))
          (let [exit-code (if-let [executable (:repl-executable final-state)]
                            (run-repl! (:repl-id final-state) executable)
                            (run-repl! (:repl-id final-state)))]
            (if (#{0 130} exit-code)
              (recur (repl-menu-state-after-exit final-state) false)
              exit-code))

          (:done? final-state)
          (or (:exit-code final-state) 0)

          :else
          (or (:exit-code final-state) 0))))))

(defn- current-bb-edn-path
  []
  (-> (io/file "bb.edn") .getAbsolutePath))

(defn- run-tasks!
  []
  (let [{:keys [status path tasks error]} (tasks/discover (current-bb-edn-path))]
    (case status
      :missing
      (do
        (binding [*out* *err*]
          (println "No bb.edn (with tasks) was found in:")
          (println (-> (io/file ".") .getCanonicalPath)))
        1)

      :invalid
      (do
        (binding [*out* *err*]
          (println "Invalid bb.edn:")
          (println path)
          ;; ERROR
          (println error))
        1)

      :ok
      (if (seq tasks)
        (tasks/run-picker! tasks)
        (do
          (println "No public bb tasks found in:")
          (println path)
          0)))))

(defn run-cli!
  [& args]
  (case (vec args)
    []
    (run-wizard!)

    (["tasks"] ["t"])
    (if (preflight! ["bb"])
      (run-tasks!)
      1)

    (["-h"] ["--help"])
    (do
      (print (usage))
      0)

    (["-version"] ["--version"] ["version"])
    (do
      (println "jus" version)
      0)

    (do
      (binding [*out* *err*]
        (print (usage)))
      1)))

(defn -main [& args]
  (let [exit-code (apply run-cli! args)]
    (when-not (zero? exit-code)
      (System/exit exit-code))))
