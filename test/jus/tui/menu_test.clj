(ns jus.tui.menu-test
  (:require [clojure.test :refer [deftest is]]
            [jus.tui.menu :as menu]))

(deftest visible-window-follows-focus-and-counts-hidden-items
  (is (= {:items [:c :d :e]
          :start 2
          :hidden-above 2
          :hidden-below 2}
         (menu/visible-window [:a :b :c :d :e :f :g] 4 3))))

(deftest visible-window-normalizes-boundary-inputs
  (is (= {:items []
          :start 0
          :hidden-above 0
          :hidden-below 0}
         (menu/visible-window [] 4 0)))
  (is (= {:items [:a]
          :start 0
          :hidden-above 0
          :hidden-below 2}
         (menu/visible-window [:a :b :c] -4 0)))
  (is (= {:items [:c]
          :start 2
          :hidden-above 2
          :hidden-below 0}
         (menu/visible-window [:a :b :c] 20 1))))

(deftest render-window-automatically-surrounds-visible-items-with-overflow
  (is (= ["↑ 2" "2:c" "3:d" "4:e" "↓ 2"]
         (:lines
          (menu/render-window [:a :b :c :d :e :f :g]
                              4
                              3
                              5
                              (fn [index item] (str index ":" (name item)))
                              (fn [arrow hidden] (str arrow " " hidden)))))))

(deftest render-window-enforces-its-terminal-row-budget
  (let [rendered (menu/render-window [:a :b :c :d :e]
                                     2
                                     3
                                     4
                                     (fn [_ item] [(name item) "detail"])
                                     (fn [arrow hidden] (str arrow " " hidden)))]
    (is (<= (count (:lines rendered)) 4))
    (is (some #{"c"} (:lines rendered)))
    (is (= "↑ 2" (first (:lines rendered))))
    (is (= "↓ 2" (last (:lines rendered))))))

(deftest render-window-prioritizes-focus-in-minimum-row-budgets
  (doseq [row-capacity [1 2]]
    (let [rendered (menu/render-window [:a :b :c :d :e]
                                       2
                                       3
                                       row-capacity
                                       (fn [_ item] [(name item) "detail"])
                                       (fn [arrow hidden] (str arrow " " hidden)))]
      (is (<= (count (:lines rendered)) row-capacity))
      (is (some #{"c"} (:lines rendered)))))
  (is (= ["↑ 2" "c"]
         (:lines (menu/render-window [:a :b :c :d :e]
                                     2
                                     3
                                     2
                                     (fn [_ item] [(name item) "detail"])
                                     (fn [arrow hidden] (str arrow " " hidden)))))))
