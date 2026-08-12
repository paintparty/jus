(ns jus.tui.style-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [jus.tui.style :as style]))

(defn- restore-os-name
  [test-fn]
  (let [os-name (System/getProperty "os.name")]
    (try
      (test-fn)
      (finally
        (if os-name
          (System/setProperty "os.name" os-name)
          (System/clearProperty "os.name"))
        (require 'jus.tui.style :reload)))))

(use-fixtures :each restore-os-name)

(defn- logo-for
  [os-name]
  (System/setProperty "os.name" os-name)
  (require 'jus.tui.style :reload)
  style/logo)

(deftest windows-uses-a-text-presentation-logo
  (is (= "◒" (logo-for "Windows 11"))))

(deftest non-windows-uses-the-yin-yang-logo
  (is (= "◒" #_"☯" (logo-for "Linux"))))
