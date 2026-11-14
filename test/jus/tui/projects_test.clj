(ns jus.tui.projects-test
  (:require [babashka.fs :as fs]
            [charm.ansi.width :as ansi-width]
            [charm.message :as msg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jus.tui.config :as config]
            [jus.tui.core :as core]
            [jus.tui.markdown :as markdown]
            [jus.tui.projects :as projects])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileTime)))

(defn- temporary [f]
  (let [root (fs/real-path (fs/create-temp-dir {:prefix "jus-projects-"}))]
    (try (f root) (finally (fs/delete-tree root)))))

(defn- write-at! [root name timestamp]
  (let [path (fs/path root name)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) name)
    (Files/setLastModifiedTime path (FileTime/fromMillis timestamp))
    path))

(defn- event [state key]
  (binding [core/*skip-flash* true]
    (core/update-fn state (msg/key-press key))))

(defn- complete [[state command]]
  (if command
    (core/update-fn state ((:fn command)))
    [state nil]))

(deftest readme-layout-is-cached-and-resize-results-cannot-overwrite-newer-layouts
  (let [calls (atom [])
        readme {:path "/tmp/README.md" :text "# Hello" :width 80
                :lines (vec (map #(str "line " %) (range 80)))}
        state (assoc (core/main-menu-state {}) :step :project-readme :readme readme)]
    (with-redefs [markdown/render-readme
                  (fn [source width]
                    (swap! calls conj width)
                    (assoc source :width width :lines [(str "width " width)]))]
      (core/view state)
      (event state :page-down)
      (is (empty? @calls))
      (let [[narrow narrow-command] (core/update-fn state {:type :window-size :width 50 :height 24})
            [wide wide-command] (core/update-fn narrow {:type :window-size :width 90 :height 24})
            [rendered _] (complete [wide wide-command])
            [stale _] (core/update-fn rendered ((:fn narrow-command)))
            [back _] (event wide :escape)
            [late _] (core/update-fn back ((:fn wide-command)))]
        (is (= 90 (get-in rendered [:readme :width])))
        (is (= rendered stale))
        (is (= back late))
        (is (= :project-tasks (:step late)))))))

(deftest glow-renders-markdown-and-image-placeholders-at-pane-width
  (when (fs/which "glow")
    (let [source (str "# Preview\n\n## Second\n\n### Third\n\n`inline-code`\n\n"
                      "**Bold** and *emphasis*.\n\n"
                      "- [x] Finished\n\n"
                      "| Name | Description |\n| --- | --- |\n"
                      "| Demo | A table cell with enough words to wrap |\n\n"
                      "```html\n<img src='code-example'>\n```\n\n"
                      "![alt](assets/image.png#dark)\n\n![][reference]\n\n[reference]: assets/other.png\n\n"
                      "<div><img src='https://example.com/assets/html-image.svg?width=100'></div>\n\n"
                      "![unicode](assets/%E7%8C%AB%20photo.png)\n\n"
                      "<!-- hidden comment -->\n")]
      (doseq [width [40 80]]
        (let [rendered (markdown/render-readme {:path "/tmp/README.md" :text source} width)
              output (str/join "\n" (:lines rendered))
              plain (ansi-width/strip-ansi output)]
          (is (nil? (:notice rendered)))
          (is (str/includes? plain "Preview"))
          (is (not (str/includes? plain "# Preview")))
          (is (str/includes? plain "[✓] Finished"))
          (is (str/includes? plain "Name"))
          (is (str/includes? plain "<img src='code-example'>"))
          (doseq [name ["image.png" "other.png" "html-image.svg" "猫 photo.png"]]
            (is (str/includes? plain (str "│ image: " name "  │"))))
          (is (not (str/includes? plain "assets/")))
          (is (not (str/includes? plain "width=100")))
          (is (str/includes? output "\033[1;7m \033[0m\033[1;7mPreview\033[0m\033[1;7m \033[0m"))
          (is (str/includes? output "\033[1;7m \033[0m\033[1;7mSecond\033[0m\033[1;7m \033[0m"))
          (is (str/includes? output "\033[1mThird\033[0m"))
          (is (some #(str/starts-with? % "inline-code") (:lines rendered)))
          (let [box-start (.indexOf ^java.util.List (:lines rendered)
                                    "\033[2m┌───────────────────┐\033[0m")
                box (subvec (:lines rendered) box-start (+ box-start 3))]
            (is (every? #(and (str/starts-with? % "\033[2m")
                              (str/ends-with? % "\033[0m")) box))
            (is (= "│ image: image.png  │" (ansi-width/strip-ansi (second box))))
            (is (str/blank? (ansi-width/strip-ansi (nth (:lines rendered) (dec box-start)))))
            (is (not (str/blank? (ansi-width/strip-ansi (nth (:lines rendered) (- box-start 2))))))
            (is (str/blank? (ansi-width/strip-ansi (nth (:lines rendered) (+ box-start 3)))))
            (is (not (str/blank? (ansi-width/strip-ansi (nth (:lines rendered) (+ box-start 4)))))))
          (is (not (str/includes? plain "hidden comment")))
          (is (every? #(<= (ansi-width/string-width %) (- width 9)) (:lines rendered))))))))

(deftest recent-projects-are-bounded-and-the-rest-sort-alphabetically
  (let [now 2000000000
        entries (concat
                 (for [n (range 7)]
                   {:label (str "project-" n) :path (str "/" n) :modified-at (- now n)})
                 [{:label "aaa" :path "/old" :modified-at 1}
                  {:label "Missing" :path "/gone" :missing? true :modified-at now}])
        {:keys [recent other]} (projects/sections entries now)]
    (is (= ["/0" "/1" "/2" "/3" "/4"] (mapv :path recent)))
    (is (= ["aaa" "Missing" "project-5" "project-6"] (mapv :label other)))
    (is (= 9 (count (set (map :path (concat recent other))))))))

(deftest activity-follows-nested-source-files-and-skips-generated-trees-and-links
  (temporary
   (fn [root]
     (let [project (fs/create-dirs (fs/path root "project"))]
       (write-at! project "src/core.clj" 1000)
       (write-at! project "test/core_test.clj" 2000)
       (write-at! project "target/generated.clj" 999999)
       (write-at! project ".git/index" 999999)
       (write-at! root "outside/new.clj" 999999)
       (fs/create-sym-link (fs/path project "linked") (fs/path root "outside"))
       (is (= {:modified-at 2000 :activity-limited? false} (projects/activity project)))
       (write-at! project "src/core.clj" 3000)
       (is (= 3000 (:modified-at (projects/activity project))))))))

(deftest catalog-deduplicates-canonical-paths-and-keeps-unavailable-projects
  (temporary
   (fn [root]
     (let [project (fs/create-dirs (fs/path root "project"))
           alias (fs/path root "alias")]
       (write-at! project "README.md" 1000)
       (fs/create-sym-link alias project)
       (let [{:keys [recent other]} (projects/catalog [(str project) (str alias)
                                                       (str (fs/path root "missing"))]
                                                      2000000000)]
         (is (empty? recent))
         (is (= 2 (count other)))
         (is (= 1 (count (filter :missing? other)))))))))

(deftest readme-prefers-markdown-and-reports-an-absent-readme
  (temporary
   (fn [root]
     (is (thrown-with-msg? Exception #"No README" (projects/readme root)))
     (spit (str (fs/path root "readme.txt")) "Plain README")
     (spit (str (fs/path root "README.md")) "# Markdown README")
     (is (= "# Markdown README" (:text (projects/readme root)))))))

(deftest adding-projects-preserves-the-config-and-its-comments
  (temporary
   (fn [root]
     (let [path (fs/path root "config.edn")
           project (fs/create-dirs (fs/path root "project with spaces"))
           source "{;; personal settings\n :custom {:keep true}\n :tui {:projects {:groups [io.github.me]\n ;; keep this list\n :paths []}}}\n"]
       (spit (str path) source)
       (let [saved (config/add-project! path (str project) source)
             updated (slurp (str path))]
         (is (= [(projects/canonical-path (str project))] (config/project-paths saved)))
         (is (= ['io.github.me] (config/groups saved)))
         (is (= {:keep true} (:custom saved)))
         (is (str/includes? updated ";; personal settings"))
         (is (str/includes? updated ";; keep this list"))
         (is (= saved (config/load-config path)))
         (config/add-project! path (str project) updated)
         (is (= updated (slurp (str path)))))))))

(deftest adding-projects-creates-config-and-does-not-overwrite-concurrent-or-malformed-edits
  (temporary
   (fn [root]
     (let [path (fs/path root "config/config.edn")
           project (fs/create-dirs (fs/path root "project"))]
       (config/add-project! path (str project) nil)
       (is (= [(str project)] (config/project-paths (config/load-config path))))
       (let [old (config/update-source path)]
         (spit (str path) "{:custom :new}")
         (is (thrown-with-msg? Exception #"Config changed" (config/add-project! path (str project) old)))
         (is (= "{:custom :new}" (slurp (str path)))))
       (spit (str path) "{:broken")
       (is (thrown? Exception (config/add-project! path (str project) "{:broken")))
       (is (= "{:broken" (slurp (str path))))))))

(deftest dashboard-add-read-and-return-flow
  (temporary
   (fn [root]
     (let [path (fs/path root "config.edn")
           project (fs/create-dirs (fs/path root "my-project"))
           readme (fs/path project "README.md")]
       (spit (str readme) (str/join "\n" (map #(str "README line " %) (range 80))))
       (with-redefs [config/global-config-path (constantly path)]
         (let [initial (assoc (core/main-menu-state {})
                              :menu-idx (.indexOf (core/main-menu-choices) :projects))
               [dashboard _] (complete (event initial :enter))
               [location _] (event dashboard :enter)
               [browser _] (event location :enter)
               _ (is (= (str (fs/expand-home "~")) (:nav-path browser)))
               [confirm _] (event (assoc browser :nav-path (str project)
                                         :nav-items (core/build-browse-items project)) :enter)
               rendered (core/strip-ansi (core/view (assoc confirm :term-width 160)))
               [cancelled _] (event confirm :escape)
               _ (is (not (fs/exists? path)))
               [saved load-command] (complete (event confirm :enter))
               [listed _] (complete [saved load-command])
               [tasks _] (event listed :enter)
               [viewer _] (complete (event tasks :enter))
               [paged _] (event viewer :page-down)
               [end _] (event paged :end)
               [back _] (event end :escape)
               [back-list _] (event back :escape)]
           (is (= :project-browse (:step cancelled)))
           (is (str/includes? rendered (str project)))
           (is (str/includes? rendered (str path)))
           (is (str/includes? rendered "[:tui :projects :paths]"))
           (is (= [(str project)] (config/project-paths (config/load-config path))))
           (is (= :project-tasks (:step tasks)))
           (is (= :project-readme (:step viewer)))
           (is (pos? (:readme-offset paged)))
           (is (str/includes? (core/strip-ansi (core/view end)) "README line 79"))
           (is (< (count (str/split-lines (core/view viewer))) (:term-height viewer)))
           (is (= :project-tasks (:step back)))
           (is (= :projects (:step back-list)))
           (is (= 1 (:menu-idx back-list)))
           (fs/delete readme)
           (let [[missing _] (complete (event tasks :enter))]
             (is (= :project-tasks (:step missing)))
             (is (str/includes? (:error missing) "No README")))))))))

(deftest dashboard-location-choice-starts-from-favorites-or-home
  (temporary
   (fn [root]
     (let [favorite (fs/create-dirs (fs/path root "favorite"))
           missing (str (fs/path root "missing"))
           cfg (config/project-config {:parent-dirs [(str favorite) missing]})
           state (assoc (core/main-menu-state cfg) :step :projects
                        :catalog {:recent [] :other []})
           [choice _] (event state :enter)
           choice-screen (core/strip-ansi (core/view choice))
           [favorites _] (event choice :enter)
           [browser _] (event favorites :enter)
           [back-favorites _] (event browser :escape)
           [back-choice _] (event back-favorites :escape)
           [browse-choice _] (event back-choice :down)
           [home _] (event browse-choice :enter)
           [back-browse _] (event home :escape)
           [missing-choice _] (event favorites :down)
           [unavailable _] (event missing-choice :enter)]
       (is (= :project-location-choice (:step choice)))
       (is (str/includes? choice-screen "Choose from my favorites..."))
       (is (str/includes? choice-screen "Browse file tree..."))
       (is (= :project-favorites (:step favorites)))
       (is (= :project-browse (:step browser)))
       (is (= (str favorite) (:nav-path browser)))
       (is (= :project-favorites (:step back-favorites)))
       (is (= 0 (:menu-idx back-favorites)))
       (is (= :project-location-choice (:step back-choice)))
       (is (= (projects/canonical-path (str (fs/expand-home "~"))) (:nav-path home)))
       (is (= :project-location-choice (:step back-browse)))
       (is (= 1 (:menu-idx back-browse)))
       (is (= :project-favorites (:step unavailable)))
       (is (str/includes? (:error unavailable) "unavailable"))))))

(deftest project-confirmation-aligns-fields-and-uses-explicit-consent
  (let [state (assoc (core/main-menu-state {}) :step :project-add-confirm
                     :pending-project "/Users/jc/Dropbox/Dev/cljc/lasertag"
                     :project-config-path "/Users/jc/.config/jus/config.edn")
        screen (core/strip-ansi (core/view state))]
    (is (str/includes? screen "  Project:    /Users/jc/Dropbox/Dev/cljc/lasertag\n"))
    (is (str/includes? screen "  Config:     /Users/jc/.config/jus/config.edn\n"))
    (is (str/includes? screen "  Append to:  [:tui :projects :paths]\n"))
    (is (str/includes? screen "> Yes, add project"))
    (is (not (str/includes? screen "Existing settings are kept")))))

(deftest project-catalog-puts-add-first-and-insets-the-section-divider
  (let [catalog {:recent [{:label "bazlib" :path "/bazlib"}]
                 :other [{:label "aaa" :path "/aaa"}]}
        state (assoc (core/main-menu-state {}) :step :projects :catalog catalog
                     :menu-idx 1 :term-height 40)
        screen (core/strip-ansi (core/view state))
        dividers (filter #(re-matches #" │    ─+    │" %)
                         (str/split-lines screen))]
    (is (< (str/index-of screen "Add a new project") (str/index-of screen "> bazlib")))
    (is (< (str/index-of screen "> bazlib") (str/index-of screen "aaa")))
    (is (= 1 (count dividers)))
    (is (not (str/includes? screen "├")))
    (is (not (str/includes? screen "14 days")))
    (is (str/includes? screen "R: refresh projects list"))))

(deftest opening-and-refreshing-projects-reloads-config-and-selects-the-first-project
  (temporary
   (fn [root]
     (let [config-path (fs/path root "config.edn")
           first-project (fs/create-dirs (fs/path root "first"))
           second-project (fs/create-dirs (fs/path root "second"))
           write-config! (fn [paths]
                           (spit (str config-path) (pr-str {:tui {:projects {:paths paths}}})))
           initial (assoc (core/main-menu-state {})
                          :menu-idx (.indexOf (core/main-menu-choices) :projects))]
       (spit (str (fs/path first-project "README.md")) "first")
       (spit (str (fs/path second-project "README.md")) "second")
       (with-redefs [config/global-config-path (constantly config-path)]
         (write-config! [(str first-project)])
         (let [[opened _] (complete (event initial :enter))
               [main _] (event opened :escape)]
           (is (= 1 (:menu-idx opened)))
           (is (str/includes? (core/strip-ansi (core/view opened)) "> first"))
           (write-config! [(str second-project)])
           (let [[reopened _] (complete (event main :enter))]
             (is (= [(str second-project)] (config/project-paths (:global-config reopened))))
             (is (str/includes? (core/strip-ansi (core/view reopened)) "> second"))
             (write-config! [])
             (let [[refreshed _] (complete (event reopened "r"))]
               (is (= 0 (:menu-idx refreshed)))
               (is (= [] (config/project-paths (:global-config refreshed))))
               (is (str/includes? (core/strip-ansi (core/view refreshed)) "> Add a new project"))))))))))
