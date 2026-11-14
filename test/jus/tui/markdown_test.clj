(ns jus.tui.markdown-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jus.tui.markdown :as markdown]))

(deftest html-fallbacks-leave-code-and-comments-intact
  (let [source (str "<img src='logo.svg'>\nA<br>B\n"
                    "<!-- <img src='hidden'> -->\n"
                    "```html\n<img src='example'>\n```\n"
                    "~~~~html\n<br>\n~~~~\n"
                    "    <img src='indented'>\n"
                    "`<img src='inline'>`\n"
                    "``a `<br>` b``\n")
        input (atom nil)]
    (with-redefs [markdown/glow-output (fn [text width directory]
                                        (reset! input [text width directory])
                                        "\033[1mRendered\033[0m\n")]
      (let [result (markdown/render-readme {:path "/tmp/README.MD" :text source} 60)
            [prepared width directory] @input]
        (is (= 51 width))
        (is (= "/tmp" directory))
        (is (= ["\033[1mRendered\033[0m"] (:lines result)))
        (is (nil? (:notice result)))
        (is (str/includes? prepared "\ue000"))
        (is (str/includes? prepared "A\nB"))
        (doseq [literal ["<!-- <img src='hidden'> -->"
                         "```html\n<img src='example'>\n```"
                         "~~~~html\n<br>\n~~~~"
                         "    <img src='indented'>"
                         "`<img src='inline'>`" "``a `<br>` b``"]]
          (is (str/includes? prepared literal)))))))

(deftest failures-and-non-markdown-readmes-stay-readable
  (let [calls (atom 0)]
    (with-redefs [markdown/glow-output (fn [& _]
                                        (swap! calls inc)
                                        (throw (ex-info "Unavailable" {})))]
      (let [fallback (markdown/render-readme {:path "README.md" :text "A readable README"} 60)
            plain (markdown/render-readme {:path "README.txt" :text "Plain README"} 60)]
        (is (= ["A readable README"] (:lines fallback)))
        (is (str/includes? (:notice fallback) "Glow"))
        (is (= ["Plain README"] (:lines plain)))
        (is (nil? (:notice plain)))
        (is (= 1 @calls))))))
