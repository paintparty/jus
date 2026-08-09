(ns charm.render.core-test
  (:require [charm.render.core :as render]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.jline.terminal TerminalBuilder]))

(defn- test-terminal
  []
  (let [in  (ByteArrayInputStream. (byte-array 0))
        out (ByteArrayOutputStream.)
        terminal (-> (TerminalBuilder/builder)
                     (.dumb true)
                     (.streams in out)
                     (.build))]
    {:out out
     :terminal terminal}))

(defn- rendered-output
  [content]
  (let [{:keys [out terminal]} (test-terminal)
        renderer (render/create-renderer terminal)]
    (render/render! renderer content)
    (.flush terminal)
    (String. (.toByteArray out))))

(deftest render-clears-below-visible-content
  (testing "render! clears stale content without advancing past the final row"
    (let [output (rendered-output "one")]
      (is (str/includes? output "one"))
      (is (str/includes? output "one\u001b[K\u001b[J")))))

(deftest render-clears-the-rest-of-each-updated-row
  (let [{:keys [out terminal]} (test-terminal)
        renderer (render/create-renderer terminal)]
    (render/render! renderer "Project template\nA much longer old row\nAnother old row")
    (render/render! renderer "Main menu\nNew project")
    (.flush terminal)
    (let [output (String. (.toByteArray out) "UTF-8")]
      (is (str/includes?
           output
           "Main menu\u001b[K\r\nNew project\u001b[K\u001b[J")))))

(deftest inline-rendering-keeps-the-terminal-scrollback-position
  (let [{:keys [out terminal]} (test-terminal)
        renderer (render/create-renderer terminal :alt-screen false)]
    (render/render! renderer "first frame\nsecond row")
    (render/render! renderer "updated frame")
    (.flush terminal)
    (let [output (String. (.toByteArray out) "UTF-8")]
      (is (not (str/includes? output "\u001b[H")))
      (is (str/includes? output "\u001b[1A\rupdated frame")))))

(deftest repeated-single-line-frames-stay-on-the-current-terminal-row
  (let [{:keys [out terminal]} (test-terminal)
        renderer (render/create-renderer terminal :alt-screen false)]
    (render/render! renderer "?")
    (render/render! renderer "? S")
    (.flush terminal)
    (let [output (String. (.toByteArray out) "UTF-8")]
      ;; CSI 0 A still means cursor-up one row in ANSI terminals.
      (is (not (str/includes? output "\u001b[0A")))
      (is (str/includes? output "\r? S")))))

(deftest terminal-height-content-does-not-scroll-the-last-row
  (let [{:keys [out terminal]} (test-terminal)
        renderer (render/create-renderer terminal)]
    (render/update-size! renderer 5 3)
    (render/render! renderer "one\ntwo\nthree")
    (.flush terminal)
    (let [output (String. (.toByteArray out) "UTF-8")]
      (is (str/includes?
           output
           "one\u001b[K\r\ntwo\u001b[K\r\nthree\u001b[K\u001b[J"))
      (is (not (str/includes? output "three\u001b[K\r\n"))))))

(deftest alternate-screen-resize-clears-the-screen-before-rendering-the-next-frame
  (let [{:keys [out terminal]} (test-terminal)
        renderer (render/create-renderer terminal :alt-screen true)]
    (render/render! renderer "old")
    (render/update-size! renderer 100 24)
    (render/render! renderer "new")
    (.flush terminal)
    (let [output (String. (.toByteArray out) "UTF-8")]
      (is (str/includes? output "\u001b[2J"))
      (is (< (.indexOf output "old")
             (.indexOf output "\u001b[2J")
             (.indexOf output "new"))))))
