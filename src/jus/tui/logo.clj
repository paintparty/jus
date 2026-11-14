(ns jus.tui.logo
  (:require [babashka.process :refer [shell]]
            [clojure.string :as str]))

(def windows-10?
  (delay
    (try
      (and (str/starts-with? (str/lower-case (System/getProperty "os.name" ""))
                             "windows")
           (let [build (-> (shell {:out :string} "cmd" "/c" "ver")
                           :out
                           (->> (re-find #"\[Version \d+\.\d+\.(\d+)"))
                           second
                           parse-long)]
             (< build 22000)))
      (catch Exception _
        false))))

(defn for-os
  "Select the logo, retaining an ASCII fallback for Windows 10 terminals."
  ([os-name] (for-os os-name false))
  ([os-name windows-10?]
   (cond
     windows-10? "*"
     (str/starts-with? (str/lower-case (or os-name "")) "mac") "☯"
     :else "◒")))

(def glyph (for-os (System/getProperty "os.name") @windows-10?))
