(ns jus.tui.animation
  (:require [charm.message :as msg]
            [charm.program :as program]
            [clojure.string :as str]
            [jus.tui.style :as style]))

(def main-menu-logo-position
  {:row 1 :column 2})

(defn- main-menu-logo-prefix
  []
  (str (apply str (repeat (:row main-menu-logo-position) "\n"))
       (apply str (repeat (:column main-menu-logo-position) " "))))

;; (def confetti-animation :laser-rays)
(def confetti-animation :polar)
(def confetti-total-frames 142)
(def confetti-frame-rate 10)
(def confetti-bottom-padding 0)
(def confetti-center-point main-menu-logo-position)
(def confetti-cell-aspect-ratio 2.0)
(def sparse-ray? false)
(def sparse-drop-even? false)
(def confetti-ray-char style/logo)
(def confetti-ray-leading-char "●")
(def confetti-ray-visible-chars 16)
(def ^:private confetti-ray-gradient-profile
  [:none :dim :dim :none :dim :none :none :none])
(def confetti-ray-trail-spacing 3)
(def confetti-ray-max-gap 4.0)
(def confetti-adaptive-ray-min-angle 0.0)
(def confetti-adaptive-ray-max-angle (/ Math/PI 2.0))
(def confetti-direction :out)
(def reverse-confetti-total-frames 42)
(def reverse-confetti-frame-rate 8)
(def post-confetti-blank-screen-pause 250)
(def opening-animation? true)

(defn- opening-header-animation-frame
  [word word-style]
  (str (main-menu-logo-prefix)
       (style/accent (str style/logo (when (seq word) " ")))
       (when (seq word)
         (word-style word))))

(def opening-header-animation-frames
  (mapv (fn [[word word-style]]
          (opening-header-animation-frame word word-style))
        [[nil style/italic]
         [nil style/italic]
         ["j" style/italic]
         ["ju" style/italic]
         ["jus" style/italic]
         ["jus" style/italic]
         ["jus" style/italic]]))

(def opening-header-animation-frame-rate 60)

(defn- inward-confetti?
  [direction]
  (case (or direction :out)
    :in true
    :out false
    (throw (ex-info "Unknown confetti direction"
                    {:direction direction
                     :supported #{:in :out}}))))

(defn- confetti-frame-rate-for
  [direction]
  (if (inward-confetti? direction)
    reverse-confetti-frame-rate
    confetti-frame-rate))

(defn confetti-padding
  "Return terminal-cell insets for the confetti canvas."
  [term-width term-height]
  (let [_width (max 0 (or term-width 0))
        height (max 0 (or term-height 0))]
    {:x      0
     :y      0
     :bottom (min confetti-bottom-padding (max 0 (dec height)))}))

(defn- confetti-layout
  [term-width term-height]
  (let [width        (if (pos? (or term-width 0)) term-width 80)
        height       (if (pos? (or term-height 0)) term-height 24)
        padding      (confetti-padding width height)
        x-padding    (:x padding)
        y-padding    (:y padding)
        bottom-padding (:bottom padding)
        inner-width  (max 1 (- width x-padding))
        inner-height (max 1 (- height y-padding bottom-padding))
        center-row   (-> (:row confetti-center-point)
                         (max y-padding)
                         (min (+ y-padding (dec inner-height))))
        center-column (-> (:column confetti-center-point)
                          (max x-padding)
                          (min (+ x-padding (dec inner-width))))]
    {:width        width
     :height       height
     :padding      padding
     :x-padding    x-padding
     :y-padding    y-padding
     :inner-width  inner-width
     :inner-height inner-height
     :center       {:row center-row :column center-column}}))

(def ^:private polar-reference-rows
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
   "d                        b          a          9                        7"])

(def ^:private polar-reference-height (count polar-reference-rows))
(def ^:private polar-reference-width 92)
(def ^:private polar-quadrant-track-count 13)
(def ^:private polar-track-count (* 4 polar-quadrant-track-count))

(def ^:private polar-track-extension-steps
  [[[0 3]]
   [[1 4] [0 3] [0 3] [0 3] [0 3]]
   [[1 2] [0 3] [0 3] [0 3]]
   [[1 6] [0 3]]
   [[1 6]]
   [[1 5]]
   [[1 4]]
   [[1 3]]
   [[2 5]]
   [[1 2]]
   [[2 3]]
   [[1 1]]
   [[2 1]]
   [[1 0]]])

(def ^:private polar-reference-tracks
  (reduce-kv
   (fn [tracks row line]
     (reduce-kv
      (fn [result column ch]
        (if (= \space ch)
          result
          (update result (Character/digit ch 36) conj [row column])))
      tracks
      (vec line)))
   (vec (repeat (inc polar-quadrant-track-count) []))
   polar-reference-rows))

(defn- extend-polar-track
  [id placements max-row max-column]
  (let [steps (get polar-track-extension-steps id)
        generated
        (->> [(peek placements) 0]
             (iterate
              (fn [[[row column] step-index]]
                (let [[row-step column-step]
                      (get steps (mod step-index (count steps)))]
                  [[(+ row row-step) (+ column column-step)]
                   (inc step-index)])))
             rest
             (map first)
             (take-while (fn [[row column]]
                           (and (<= row max-row) (<= column max-column))))
             (remove (fn [[row column]]
                       (and (< row polar-reference-height)
                            (< column polar-reference-width)))))]
    (into placements generated)))

(defn- polar-track-orientation
  [id]
  (cond
    (<= id polar-quadrant-track-count)
    [id 1 1]

    (<= id (* 2 polar-quadrant-track-count))
    [(- (* 2 polar-quadrant-track-count) id) 1 -1]

    (<= id (* 3 polar-quadrant-track-count))
    [(- id (* 2 polar-quadrant-track-count)) -1 -1]

    :else
    [(- polar-track-count id) -1 1]))

(defn- polar-track
  [id center width height]
  (let [[reference-id row-sign column-sign] (polar-track-orientation id)
        center-cell [(:row center) (:column center)]
        max-row-distance (max (:row center) (- (dec height) (:row center)))
        max-column-distance (max (:column center)
                                 (- (dec width) (:column center)))
        relative-placements
        (extend-polar-track reference-id
                            (get polar-reference-tracks reference-id)
                            max-row-distance
                            max-column-distance)
        placements
        (into [center-cell]
              (comp (map (fn [[row column]]
                           [(+ (:row center) (* row-sign row))
                            (+ (:column center) (* column-sign column))]))
                    (filter (fn [[row column]]
                              (and (<= 0 row (dec height))
                                   (<= 0 column (dec width))))))
              relative-placements)]
    {:id id
     :endpoint (peek placements)
     :placements placements}))

(defn confetti-grid
  "Build an addressable circular grid for a viewport."
  [term-width term-height]
  (let [{:keys [width height center]} (confetti-layout term-width term-height)
        all-tracks  (mapv #(polar-track % center width height)
                          (range polar-track-count))
        tracks      (if sparse-ray?
                      (filterv (fn [{:keys [id]}]
                                 (if sparse-drop-even?
                                   (odd? id)
                                   (even? id)))
                               all-tracks)
                      all-tracks)
        longest-track (reduce max 0 (map #(count (:placements %)) tracks))
        tracks-by-id (into {} (map (juxt :id identity)) tracks)]
    {:width        width
     :height       height
     :center       center
     :frame-count  (+ longest-track (dec confetti-ray-visible-chars))
     :tracks       tracks
     :tracks-by-id tracks-by-id}))

(defn- confetti-ray-degrees
  []
  (let [degrees (range 0 360 15)]
    (if sparse-ray?
      (keep-indexed (fn [id degrees]
                      (when (if sparse-drop-even?
                              (odd? id)
                              (even? id))
                        degrees))
                    degrees)
      degrees)))

(defn make-particle
  "Create one neutral Yin-Yang laser ray at a fixed angle."
  [degrees]
  {:angle (* Math/PI (/ degrees 180.0))})

(defn- ray-gap
  [left-angle right-angle travel]
  (Math/hypot (* travel (- (Math/cos right-angle)
                           (Math/cos left-angle)))
              (* travel (- (Math/sin right-angle)
                           (Math/sin left-angle)))))

(defn- fill-ray-gap
  [left-angle right-angle travel]
  (if (<= (ray-gap left-angle right-angle travel) confetti-ray-max-gap)
    [left-angle right-angle]
    (let [midpoint (/ (+ left-angle right-angle) 2.0)]
      (into (pop (fill-ray-gap left-angle midpoint travel))
            (fill-ray-gap midpoint right-angle travel)))))

(defn adaptive-ray-particles
  "Insert midpoint laser rays in the right/down quadrant as they spread."
  [particles travel]
  (let [adaptive-angles (->> particles
                             (map :angle)
                             (filter #(<= confetti-adaptive-ray-min-angle
                                          %
                                          confetti-adaptive-ray-max-angle))
                             sort)
        filled-angles (if (< (count adaptive-angles) 2)
                        adaptive-angles
                        (->> (partition 2 1 adaptive-angles)
                             (mapcat (fn [[left-angle right-angle]]
                                       (butlast (fill-ray-gap left-angle
                                                              right-angle
                                                              travel))))
                             (concat [(last adaptive-angles)])))
        fixed-angles (->> particles
                          (map :angle)
                          (remove #(<= confetti-adaptive-ray-min-angle
                                       %
                                       confetti-adaptive-ray-max-angle)))]
    (mapv (fn [angle] {:angle angle})
          (sort (concat fixed-angles filled-angles)))))

(defn confetti-ray-dimmed-chars
  "Return the length of a ray's solid dim tail."
  []
  (quot confetti-ray-visible-chars 2))

(defn- ray-trail-gap
  [trail-index]
  (let [gradient-tail-start (- confetti-ray-visible-chars 3)]
    (cond
      (= trail-index (dec confetti-ray-visible-chars))
      (* 4 confetti-ray-trail-spacing)

      (>= trail-index gradient-tail-start)
      (* 2 confetti-ray-trail-spacing)

      :else confetti-ray-trail-spacing)))

(defn- ray-trail-offset
  [trail-index]
  (reduce + (map ray-trail-gap (range 1 (inc trail-index)))))

(defn- ray-glyph
  [trail-index]
  (let [visible-chars (max 1 confetti-ray-visible-chars)
        tail-index (- visible-chars trail-index 1)
        dimmed-chars (confetti-ray-dimmed-chars)
        gradient-index (- tail-index dimmed-chars)
        gradient-chars (- visible-chars dimmed-chars)
        profile-size (count confetti-ray-gradient-profile)
        profile-index (if (= gradient-index (dec gradient-chars))
                        (dec profile-size)
                        (quot (* gradient-index profile-size)
                              gradient-chars))
        style (if (neg? gradient-index)
                :dim
                (get confetti-ray-gradient-profile profile-index))
        glyph (if (zero? trail-index)
                confetti-ray-leading-char
                confetti-ray-char)]
    (if (= :dim style)
      (style/dim glyph)
      #_(if style/windows?
        (str "\033[38;5;244m" glyph "\033[0m")
        (style/dim glyph))
      glyph)))

(defn- init-laser-rays-confetti
  [direction]
  {:animation      :laser-rays
   :direction      direction
   :frame          -2
   :adaptive-rays? (not sparse-ray?)
   :particles      (mapv make-particle (confetti-ray-degrees))})

(defn- init-polar-confetti
  [term-width term-height direction]
  (let [grid (confetti-grid term-width term-height)]
    (assoc grid
           :animation :polar
           :direction direction
           :frame-count (if (inward-confetti? direction)
                          reverse-confetti-total-frames
                          (:frame-count grid))
           :frame -2)))

(defn init-confetti
  ([]
   (init-confetti 80 24))
  ([term-width term-height]
   (init-confetti term-width term-height confetti-direction))
  ([term-width term-height direction]
   (inward-confetti? direction)
   (case confetti-animation
     :laser-rays (init-laser-rays-confetti direction)
     :polar (init-polar-confetti term-width term-height direction)
     (throw (ex-info "Unknown confetti animation"
                     {:animation confetti-animation
                      :supported #{:laser-rays :polar}})))))

(defn- render-polar-confetti
  [{:keys [direction frame tracks center]} term-width term-height]
  (let [{:keys [width height]} (confetti-layout term-width term-height)
        grid (vec (repeat height (vec (repeat width " "))))
        intro-char (when (neg? frame) confetti-ray-char)
        inward? (inward-confetti? direction)
        longest-track (reduce max 0 (map #(count (:placements %)) tracks))
        natural-frame-count (+ longest-track (dec confetti-ray-visible-chars))
        animation-frame (if inward?
                          (if (< frame reverse-confetti-total-frames)
                            (quot (* frame (max 0 (dec natural-frame-count)))
                                  (dec reverse-confetti-total-frames))
                            (+ natural-frame-count
                               (- frame reverse-confetti-total-frames)))
                          frame)
        center-row (:row center)
        center-column (:column center)
        grid (if intro-char
               (assoc-in grid [center-row center-column] intro-char)
               (reduce
                (fn [g {:keys [placements]}]
                  (let [track-size (count placements)
                        start-frame (- longest-track track-size)
                        head-index (if inward?
                                     (- (dec track-size)
                                        (- animation-frame start-frame))
                                     animation-frame)]
                    (reduce
                     (fn [trail trail-index]
                       (let [placement-index (if inward?
                                               (+ head-index trail-index)
                                               (- head-index trail-index))]
                         (if-let [[row column] (get placements placement-index)]
                           (assoc-in trail [row column]
                                     (ray-glyph trail-index))
                           trail)))
                     g
                     (range confetti-ray-visible-chars))))
                grid
                tracks))]
    (str/join "\n" (map str/join grid))))

(defn- render-laser-rays-confetti
  [{:keys [direction frame particles adaptive-rays?]} term-width term-height]
  (let [{:keys [width height x-padding y-padding inner-width inner-height center]}
        (confetti-layout term-width term-height)
        grid        (vec (repeat height (vec (repeat width " "))))
        intro-char  (when (neg? frame) confetti-ray-char)
        inward?     (inward-confetti? direction)
        center-row  (:row center)
        center-column (:column center)
        total-frames (if inward?
                       reverse-confetti-total-frames
                       confetti-total-frames)
        progress    (/ (double (max 0 frame)) (dec total-frames))
        max-column  (+ x-padding (dec inner-width))
        max-row     (+ y-padding (dec inner-height))
        max-travel  (max 1 (- max-column center-column))
        max-tail-offset (ray-trail-offset (dec confetti-ray-visible-chars))
        ray-travel  (if inward?
                      (- max-travel
                         (* progress (+ max-travel max-tail-offset)))
                      (* max-travel progress))
        particles   (if adaptive-rays?
                      (adaptive-ray-particles particles (max 0.0 ray-travel))
                      particles)
        grid        (if intro-char
                      (assoc-in grid [center-row center-column] intro-char)
                      (reduce
                       (fn [g particle]
                         (reduce
                          (fn [trail trail-index]
                            (let [angle  (:angle particle)
                                  trail-offset (ray-trail-offset trail-index)
                                  travel (if inward?
                                           (+ ray-travel trail-offset)
                                           (- ray-travel trail-offset))
                                  raw-column (+ center-column
                                                (* travel (Math/cos angle)))
                                  raw-row (+ center-row
                                             (/ (* travel (Math/sin angle))
                                                confetti-cell-aspect-ratio))
                                  column (Math/round (double raw-column))
                                  row    (Math/round (double raw-row))]
                              (if (and (not (neg? travel))
                                       (<= x-padding raw-column max-column)
                                       (<= y-padding raw-row max-row))
                                (assoc-in trail [row column]
                                          (ray-glyph trail-index))
                                trail)))
                          g
                          (range confetti-ray-visible-chars)))
                       grid
                       particles))]
    (str/join "\n" (map str/join grid))))

(defn render-confetti
  "Render one frame with the animation engine captured by `init-confetti`."
  ([confetti term-width]
   (render-confetti confetti term-width 24))
  ([confetti term-width term-height]
   (case (:animation confetti)
     :laser-rays (render-laser-rays-confetti confetti term-width term-height)
     :polar (render-polar-confetti confetti term-width term-height)
     (throw (ex-info "Unknown confetti animation"
                     {:animation (:animation confetti)
                      :supported #{:laser-rays :polar}})))))

(defn confetti-visible?
  "Whether a confetti frame still has a glyph inside the terminal canvas."
  [confetti term-width term-height]
  (let [canvas (render-confetti confetti term-width term-height)]
    (or (str/includes? canvas confetti-ray-char)
        (str/includes? canvas confetti-ray-leading-char))))

(defn confetti-tick-cmd
  "Async cmd that sleeps for the configured frame rate, then sends a confetti-tick message."
  ([]
   (confetti-tick-cmd confetti-direction))
  ([direction]
   (program/cmd (fn []
                  (Thread/sleep (confetti-frame-rate-for direction))
                  (msg/key-press "confetti-tick")))))

(defn opening-header-tick-cmd
  "Async cmd that advances the opening header animation."
  []
  (program/cmd (fn []
                 (Thread/sleep opening-header-animation-frame-rate)
                 (msg/key-press "opening-header-tick"))))

(defn post-confetti-blank-screen-pause-cmd
  "Async cmd that holds a blank alternate screen before the TUI exits."
  []
  (program/cmd
   (fn []
     (Thread/sleep post-confetti-blank-screen-pause)
     (msg/key-press "post-confetti-blank-screen-pause-done"))))

(defn success-pause-cmd
  "Async cmd that pauses after generation before starting confetti."
  []
  (program/cmd (fn []
                 (Thread/sleep 3000)
                 (msg/key-press "success-pause-done"))))

(defn start-opening-animation
  [state]
  (let [confetti (assoc (init-confetti (:term-width state)
                                       (:term-height state)
                                       :in)
                        :frame 0)]
    [(assoc state
            :opening-animation {:phase :confetti
                                :confetti confetti}
            :error nil)
     (confetti-tick-cmd :in)]))

(defn initialize-main-menu
  [state]
  (if opening-animation?
    (start-opening-animation state)
    [state nil]))

(defn start-success-pause
  [state]
  [(assoc state :success-pause true :error nil)
   (success-pause-cmd)])

(defn start-confetti
  [state]
  (let [confetti (init-confetti (:term-width state) (:term-height state))]
    [(assoc state
            :success-pause false
            :confetti confetti)
     (confetti-tick-cmd (:direction confetti))]))

(defn resize-confetti
  [confetti width height]
  (case (:animation confetti)
    :laser-rays confetti
    :polar (cond-> (merge confetti (confetti-grid width height))
             (inward-confetti? (:direction confetti))
             (assoc :frame-count reverse-confetti-total-frames))))

(defn resize-state
  [state {:keys [width height]}]
  (cond-> (assoc state :term-width width :term-height height)
    (:confetti state)
    (update :confetti resize-confetti width height)

    (get-in state [:opening-animation :confetti])
    (update-in [:opening-animation :confetti]
               resize-confetti width height)))

(defn update-opening-animation
  [state msg]
  (let [{:keys [phase confetti header-frame]} (:opening-animation state)]
    (cond
      (msg/key-match? msg "ctrl+c")
      [(assoc state :exit-code 130) program/quit-cmd]

      (= :confetti phase)
      (if (and (msg/key-press? msg)
               (= "confetti-tick" (:key msg)))
        (let [confetti (update confetti :frame inc)]
          (if (confetti-visible? confetti
                                 (:term-width state)
                                 (:term-height state))
            [(assoc-in state [:opening-animation :confetti] confetti)
             (confetti-tick-cmd (:direction confetti))]
            [(assoc state :opening-animation {:phase :header
                                              :header-frame 0})
             (opening-header-tick-cmd)]))
        [state nil])

      (= :header phase)
      (if (and (msg/key-press? msg)
               (= "opening-header-tick" (:key msg)))
        (let [next-frame (inc header-frame)]
          (if (< next-frame (count opening-header-animation-frames))
            [(assoc-in state [:opening-animation :header-frame] next-frame)
             (opening-header-tick-cmd)]
            [(dissoc state :opening-animation) nil]))
        [state nil])

      :else
      [(dissoc state :opening-animation) nil])))
