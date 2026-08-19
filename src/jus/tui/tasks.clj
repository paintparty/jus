(ns jus.tui.tasks
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [charm.message :as msg]
            [charm.program :as program]
            [rewrite-clj.zip :as z]))

(defn- malformed!
  [message]
  (throw (ex-info message {})))

(defn- map-pairs
  "Returns a map zipper's key/value locations in source order."
  [map-loc]
  (loop [key-loc (z/down map-loc)
         pairs   []]
    (if key-loc
      (let [value-loc (z/right key-loc)]
        (when-not value-loc
          (malformed! "Map entry is missing a value"))
        (recur (z/right value-loc) (conj pairs [key-loc value-loc])))
      pairs)))

(defn- map-value-loc
  [map-loc target-key]
  (some (fn [[key-loc value-loc]]
          (when (= target-key (z/sexpr key-loc))
            value-loc))
        (map-pairs map-loc)))

(defn- task-description
  [task-loc]
  (if-not (map? (z/sexpr task-loc))
    ""
    (let [doc-loc (map-value-loc task-loc :doc)
          doc     (some-> doc-loc z/sexpr)]
      (if (string? doc) doc ""))))

(defn- public-task?
  [task-name]
  (and (symbol? task-name)
       (not (.startsWith (name task-name) "-"))))

(defn- parse-tasks
  [contents]
  (let [root-loc  (z/of-string contents)
        root      (z/sexpr root-loc)
        tasks-loc (when (map? root) (map-value-loc root-loc :tasks))]
    (when-not (map? root)
      (malformed! "bb.edn must contain an EDN map"))
    (cond
      (nil? tasks-loc)
      []

      (not (map? (z/sexpr tasks-loc)))
      (malformed! ":tasks must be an EDN map")

      :else
      (->> (map-pairs tasks-loc)
           (keep (fn [[name-loc task-loc]]
                   (let [task-name (z/sexpr name-loc)]
                     (when (public-task? task-name)
                       {:name (name task-name)
                        :doc  (task-description task-loc)}))))
           vec))))

(defn discover
  "Structurally reads only the supplied bb.edn path without evaluating it.

   Returns ordered public tasks as
   {:status :ok :tasks [{:name string :doc string} ...]}

   Missing and invalid configurations return distinct
   structured status maps."
  [bb-edn-path]
  (let [file (io/file bb-edn-path)
        path (.getAbsolutePath file)]
    (if-not (.isFile file)
      {:status :missing :path path}
      (try
        {:status :ok
         :path   path
         :tasks  (parse-tasks (slurp file))}
        (catch Exception error
          {:status :invalid
           :path   path
           :error  (.getMessage error)})))))

(def ^:private picker-max-width 80)
(def tasks-gap 0)
(def tasks-animation-frame-rate 5)
(def tasks-reveal-frame-rate 20)
(def tasks-animation-tick "tasks-animation-tick")

(defn- primary
  [s]
  (str "\033[1m" s "\033[0m"))

(defn- secondary
  [s]
  (str "\033[2m" s "\033[0m"))

(defn- accent
  [s]
  (str "\033[1;2;m" s "\033[0m"))

(defn- picker-width
  [state]
  (max 1 (min picker-max-width (or (:term-width state) picker-max-width))))

(defn- visible-task-count
  [state]
  ;; Reserve three header lines and one separator line per task.
  (max 1 (min 10 (quot (max 1 (- (or (:term-height state) 24) 3)) 2))))

(defn- clamp
  [value lower upper]
  (max lower (min value upper)))

(defn- scroll-for-selection
  [state]
  (let [selected (:selected-idx state)
        visible  (visible-task-count state)
        maximum  (max 0 (- (count (:tasks state)) visible))]
    (cond
      (< selected (:scroll-offset state)) selected
      (>= selected (+ (:scroll-offset state) visible))
      (inc (- selected visible))
      :else (:scroll-offset state))))

(defn- normalize-scroll
  [state]
  (let [maximum (max 0 (- (count (:tasks state)) (visible-task-count state)))]
    (update state :scroll-offset #(clamp (or % 0) 0 maximum))))

(defn- move-selection
  [state delta]
  (let [maximum (max 0 (dec (count (:tasks state))))
        state   (update state :selected-idx #(clamp (+ % delta) 0 maximum))]
    (assoc state :scroll-offset (scroll-for-selection state))))

(defn- split-line
  "Wraps text at word boundaries where possible, splitting a long token only
  when necessary to honour the supplied width."
  [text width]
  (let [width (max 1 width)]
    (loop [remaining (str/trim (str text))
           lines     []]
      (cond
        (empty? remaining)
        (if (seq lines) lines [""])

        (<= (count remaining) width)
        (conj lines remaining)

        :else
        (let [prefix      (subs remaining 0 (inc width))
              break-index (.lastIndexOf prefix " ")
              split-at    (if (pos? break-index) break-index width)]
          (recur (str/trim (subs remaining split-at))
                 (conj lines (str/trim (subs remaining 0 split-at)))))))))

(defn- task-name-width
  [tasks width]
  (let [widest (apply max 1 (map #(count (:name %)) tasks))]
    ;; Keep enough space for a useful description column on normal terminals.
    ;; Names that exceed this width are wrapped without truncation.
    (min widest (max 1 (- width 12)))))

(defn- render-task
  [task selected? width name-width show-description? reveal-style]
  (let [prefix       (cond
                       (<= width 2) ""
                       selected? "> "
                       :else "  ")
        name-lines   (split-line (:name task) (- width (count prefix)))
        description  (when show-description? (:doc task))
        description-width (- width (count prefix) name-width 2)
        indent       (apply str (repeat (+ (count prefix) name-width 2) " "))
        first-name   (first name-lines)
        remaining    (next name-lines)
        doc-lines    (when-not (str/blank? description)
                       (split-line description (max 1 description-width)))
        name-line    (str prefix first-name)
        name-line    (if (and (empty? remaining)
                              (<= (count first-name) name-width)
                              (seq doc-lines))
                       (str name-line
                            (apply str (repeat (max 0 (- name-width (count first-name))) " "))
                            "  "
                            (first doc-lines))
                       name-line)
        rest-docs    (if (and (empty? remaining)
                              (<= (count first-name) name-width)
                              (seq doc-lines))
                       (next doc-lines)
                       doc-lines)]
    (let [lines (concat [name-line]
                        (map #(str (apply str (repeat (count prefix) " ")) %) remaining)
                        (map #(str indent %) rest-docs))]
      (cond
        (= :secondary reveal-style) (map secondary lines)
        selected? (map primary lines)
        :else lines))))

(def bb-task-cta
  "? Select a bb task to run:")

(def ^:private bb-task-cta-hint
  " (Use arrow keys)")

(defn- gap-lines
  []
  (repeat (max 0 tasks-gap) ""))

(defn- cta-lines
  [width cta-visible-count hint-dismissed?]
  (let [line-width (min width (max 0 cta-visible-count))
        visible    (subs bb-task-cta 0 line-width)
        show-hint? (and (not hint-dismissed?)
                        (= line-width (count bb-task-cta))
                        (<= (+ line-width (count bb-task-cta-hint)) width))]
    [(str (when (seq visible) (primary visible))
          (when show-hint? (secondary bb-task-cta-hint)))]))

(defn- picker-view
  [{:keys [tasks selected-idx scroll-offset animation-phase animation-index]
    :as state}]
  (let [width             (picker-width state)
        animation-phase   (or animation-phase :done)
        visible           (visible-task-count state)
        end               (min (count tasks) (+ scroll-offset visible))
        name-width        (task-name-width tasks width)
        show-description? (and (> (or (:term-height state) 24) 12)
                               (> width 16))
        animated?         (not= :done animation-phase)
        reveal-count      (if (= :cta animation-phase)
                            0
                            (min (count tasks) (inc (or animation-index -1))))
        shown-end          (if animated?
                             (min end (+ scroll-offset reveal-count))
                             end)
        shown             (subvec (vec tasks) scroll-offset shown-end)
        task-blocks       (mapv (fn [index task]
                                  (render-task task
                                               (= index selected-idx)
                                               width
                                               name-width
                                               show-description?
                                               (when (and (= :task-secondary animation-phase)
                                                          (= index animation-index))
                                                 :secondary)))
                                (range scroll-offset end)
                                shown)
        rows              (mapcat identity (interpose (gap-lines) task-blocks))
        up-more?          (pos? scroll-offset)
        down-more?        (and (not animated?) (< end (count tasks)))]
    (str/join "\n"
              (concat (cta-lines width (if (= :cta animation-phase)
                                         animation-index
                                         (count bb-task-cta))
                                 (:arrow-hint-dismissed? state))
                      (when (seq task-blocks) (gap-lines))
                      (when up-more? (split-line "  up more" width))
                      rows
                      (when down-more? (split-line "  down more" width))))))

(defn- tasks-animation-tick-cmd
  [frame-rate]
  (program/cmd (fn []
                 (Thread/sleep frame-rate)
                 (msg/key-press tasks-animation-tick))))

(defn- picker-init
  [tasks]
  [{:tasks         (vec tasks)
    :selected-idx  0
    :scroll-offset 0
    :moved?        false
    :arrow-hint-dismissed? false
    :term-width    picker-max-width
    :term-height   24
    :animation-phase :cta
    :animation-index 0}
   (tasks-animation-tick-cmd tasks-animation-frame-rate)])

(defn- selected-task-view
  [task]
  (str (primary bb-task-cta)
       " "
       (primary (:name task))
       "\n\n"))

(defn- advance-animation
  [state]
  (let [task-count (count (:tasks state))]
    (case (:animation-phase state)
      :cta
      (if (< (:animation-index state) (count bb-task-cta))
        [(update state :animation-index inc) true]
        [(assoc state :animation-phase (if (pos? task-count) :task-secondary :done)
                :animation-index 0)
         (pos? task-count)])

      :task-secondary
      [(assoc state :animation-phase :task-default) true]

      :task-default
      (if (< (inc (:animation-index state)) task-count)
        [(assoc state :animation-phase :task-secondary
                :animation-index (inc (:animation-index state))) true]
        [(assoc state :animation-phase :done) false])

      [state false])))

(defn- picker-update
  [state event]
  (cond
    (msg/window-size? event)
    [(-> state
         (assoc :term-width (:width event) :term-height (:height event))
         normalize-scroll)
     nil]

    (msg/key-match? event "ctrl+c")
    [(assoc state :exit-code 130) program/quit-cmd]

    (msg/key-match? event :escape)
    [(assoc state :exit-code 0) program/quit-cmd]

    (msg/key-match? event tasks-animation-tick)
    (let [[state continue?] (advance-animation state)]
      [state (when continue?
               (tasks-animation-tick-cmd
                (if (= :cta (:animation-phase state))
                  tasks-animation-frame-rate
                  tasks-reveal-frame-rate)))])

    (or (msg/key-match? event :up) (msg/key-match? event "k"))
    [(assoc (move-selection state -1) :moved? true) nil]

    (or (msg/key-match? event :down) (msg/key-match? event "j"))
    [(assoc (move-selection state 1)
            :moved? true
            :arrow-hint-dismissed? true)
     nil]

    (msg/key-match? event :enter)
    [(assoc state :selected-task (get (:tasks state) (:selected-idx state)))
     program/quit-cmd]

    :else
    [state nil]))

(defn- start-task-process!
  [task-name]
  (try
    (let [process (-> (ProcessBuilder. ^java.util.List ["bb" "run" task-name])
                      (.inheritIO)
                      (.start))]
      (.waitFor process))
    (catch java.io.IOException _
      1)))

(defn- run-task!
  [task-name]
  (println)
  (start-task-process! task-name))

(defn run-picker!
  "Runs the inline task picker for discovered tasks and returns its exit code.

  Selecting a task runs `bb run <task>` attached directly to the terminal;
  Escape returns 0 and Ctrl-C returns 130."
  [tasks]
  (flush)
  (let [result (program/run {:init       #(picker-init tasks)
                             :update     #'picker-update
                             :view       (fn [state]
                                           (cond
                                             (:exit-code state)
                                             ""

                                             (:selected-task state)
                                             (selected-task-view (:selected-task state))

                                             :else
                                             (picker-view state)))
                             :alt-screen false})]
    (cond
      (= 130 (:exit-code result)) 130
      (:selected-task result)
      (run-task! (:name (:selected-task result)))
      :else 0)))
