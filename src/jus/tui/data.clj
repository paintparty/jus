(ns jus.tui.data)

(def community-resources
  [{:label   "Language"
    :desc    "Explore Clojure variants and dialects"
    :entries [{:label "Clojure"
               :desc  "Clojure JVM"
               :url   "https://clojure.org/"}
              {:label "Clojure CLR"
               :desc  "Clojure on the Common Language Runtime"
               :url   "https://clojure.org/about/clojureclr"}
              {:label "ClojureScript"
               :desc  "Targets JS"
               :url   "https://clojurescript.org/"}
              {:label "Babashka"
               :desc  "Instant startup, SCI, GraalVM"
               :url   "https://babashka.org/"}
              {:label "List of Clojure-likes"
               :desc  "Compiled list of Clojure dialects"
               :url   "https://github.com/chr15m/awesome-clojure-likes"}
              {:label "ClojureStar"
               :desc  "Compiled list of Clojure dialects, with REPLs"
               :url   "https://clojure.cc/"}]}

   {:label   "Documentation"
    :desc    "Docs and Guides"
    :entries [{:label "Getting Started"
               :desc  "Official"
               :url   "https://clojure.org/getting_started"}
              {:label "Grand Tour"
               :desc  "Official"
               :url   "https://clojure.org/reference"}
              {:label "APIs"
               :desc  "Official API Reference"
               :url   "https://clojure.org/api"}
              {:label "Clojure Distilled"
               :desc  "Concise introduction to the language"
               :url   "https://yogthos.net/ClojureDistilled.html"}
              {:label "Clojure Docs"
               :desc  "Docs and Examples from the community"
               :url   "https://clojuredocs.org/"}
              {:label "Guides and Tutorials"
               :desc  "Community guides and tutorials"
               :url   "https://clojure-doc.org/"}
              {:label "cljdoc"
               :desc  "Hosted autogen docs for libraries"
               :url   "https://cljdoc.org/"}]}

   {:label   "Libraries"
    :desc    "Discovering and using libraries"
    :entries [{:label "Clojars"
               :desc  "Deployment and hosting of jars"
               :url   "https://clojars.org"}
              {:label "Clojure Toolbox"
               :desc  "Discovery of libraries"
               :url   "https://www.clojure-toolbox.com/"}
              {:label "Clojure Land"
               :desc  "Discovery of libraries"
               :url   "https://clojure.land/"}
              {:label "Scicloj Resources"
               :desc  "List of data science tools & libraries"
               :url   "https://scicloj.github.io/docs/resources/libs/"}]}

   {:label   "Discussion"
    :desc    "Online forums"
    :entries [{:label "Clojurians Slack"
               :desc  "This is where most of the action is"
               :url   "https://clojurians.net"}
              {:label "Clojurians Zulip"
               :desc  "A place where the community meets"
               :url   "https://clojurians.zulipchat.com/"}
              {:label "ClojureVerse"
               :desc  "Friendly Clojure(Script) Community"
               :url   "https://clojureverse.org/"}
              {:label "Reddit"
               :desc  "r/clojure"
               :url   "https://www.reddit.com/r/Clojure/"}
              {:label "Clojure Q&A"
               :desc  "The Official Clojure Q&A forum"
               :url   "https://ask.clojure.org/"}]}

   {:label   "News & Calendar"
    :desc    "News and Current Events"
    :entries [{:label "News"
               :desc  "Official clojure.org news page, updated weekly"
               :url   "https://clojure.org/news"}
              {:label "Events"
               :desc  "Official clojure.org events, updated regularly"
               :url   "https://clojure.org/community/events"}]}

   {:label      "Development"
    :desc       "Development resources"
    :menu-label "Select a dialect"
    :entries    [{:label "Clojure (JVM)"
                  :entries
                  [{:label "Development"
                    :desc  "Official dev portal"
                    :url   "https://clojure.org/dev"}
                   {:label "Support"
                    :desc  "Place to report bugs"
                    :url   "https://clojure.atlassian.net/servicedesk/customer/portal/1"}
                   {:label "Issue tracker"
                    :desc  "Jira"
                    :url   "https://clojure.atlassian.net/projects/CLJ"}
                   {:label "Source"
                    :desc  "Clojure source code repo"
                    :url   "https://github.com/clojure/clojure"}]}
                 {:label "Babashka"
                  :entries
                  [{:label "Development"
                    :desc  "Developing Babashka Readme"
                    :url   "https://github.com/babashka/babashka/blob/master/doc/dev.md"}]}]}

   {:label "Learning"
    :desc  "Resources to learn Clojure"
    :entries
    [{:label "Learn Clojure"
      :desc  "Official getting-started guide"
      :url   "https://clojure.org/guides/learn/clojure"}

     {:label "Getting Started"
      :desc  "Official installation and setup guide"
      :url   "https://clojure.org/guides/getting_started"}

     {:label "Working with the REPL"
      :desc  "Official REPL workflow guide"
      :url   "https://clojure.org/guides/repl/introduction"}

     {:label "Books"
      :desc  "Official list of Clojure books"
      :url   "https://clojure.org/community/books"}

     {:label "Clojure Camp"
      :desc  "Community mentoring"
      :url   "https://clojure.camp"}

     {:label "ClojureDocs"
      :desc  "Community examples and API documentation"
      :url   "https://clojuredocs.org"}

     {:label "Clojure-Doc"
      :desc  "Guides, tutorials, and ecosystem documentation"
      :url   "https://clojure-doc.org"}

     {:label "Exercism"
      :desc  "Interactive exercises with mentoring"
      :url   "https://exercism.org/tracks/clojure"}

     {:label "4ever-clojure"
      :desc  "Classic Clojure programming exercises"
      :url   "https://4clojure.oxal.org"}

     {:label "ClojureStream"
      :desc  "Video courses and workshops"
      :url   "https://clojure.stream"}

     {:label "Practicalli"
      :desc  "Guides, books, and video tutorials"
      :url   "https://practical.li"}

     {:label "Clojure Koans"
      :desc  "Learn Clojure through failing tests"
      :url   "https://github.com/functional-koans/clojure-koans"}

     {:label "ClojureBridge"
      :desc  "Workshops for newcomers"
      :url   "https://clojurebridge.org"}

     {:label "Awesome Clojure"
      :desc  "Curated list of Clojure resources"
      :url   "https://github.com/razum2um/awesome-clojure"}

     {:label "Awesome Clojure REPL"
      :desc  "Curated REPL tooling and workflow resources"
      :url   "https://github.com/narkisr/awesome-clojure-repl"}

     {:label "Awesome ClojureScript"
      :desc  "Curated learning resources for ClojureScript"
      :url   "https://github.com/hantuzun/awesome-clojurescript"}

     {:label "Excellent Clojure"
      :desc  "Opinionated curated resource collection"
      :url   "https://gist.github.com/ssrihari/0bf159afb781eef7cc552a1a0b17786f"}]}

   {:label   "Conferences"
    :desc    "Past and ongoing conferences"
    :entries [{:label "Clojure/Conj"
               :desc  "The official Clojure conference"
               :url   "https://clojure-conj.org"}

              {:label "Dutch Clojure Days"
               :desc  "Annual community conference in the Netherlands"
               :url   "https://clojuredays.org"}

              {:label "Babashka Conf"
               :desc  "Babashka and adjacent tooling"
               :url   "https://babashka.org/conf/"}

              {:label "EuroClojure"
               :desc  "Historic European Clojure conference, returning in 2027"
               :url   "https://euroclojure.org"}

              {:label "Heart of Clojure"
               :desc  "Organized by Lambda Island, in Belgium"
               :url   "https://2024.heartofclojure.eu"}

              {:label "IN/Clojure"
               :desc  "Annual Clojure conference in India"
               :url   "https://inclojure.org"}

              {:label "Clojure South"
               :desc  "Regional conference in the southern United States"
               :url   "https://clojure-south.com"}

              {:label "re:Clojure"
               :desc  "London"
               :url   "https://reclojure.org"}

              {:label "Lambda Days"
               :desc  "Functional programming conference"
               :url   "https://lambdadays.org"}]}

   {:label "Podcasts"
    :desc  "Podcasts"
    :entries
    [{:label "defn"
      :desc  "Interviews with devs and community members"
      :url   "https://zencastr.com/defn"}

     {:label "Functional Design in Clojure"
      :desc  "Software design and functional programming"
      :url   "https://clojuredesign.club/"}

     {:label "Apropos Clojure"
      :desc  "Interviews, panel discussions, and live coding"
      :url   "https://www.youtube.com/@aproposclojure6984"}

     {:label "ClojureStream"
      :desc  "Interviews with community members"
      :url   "https://clojure.stream/podcast"}

     {:label "The REPL"
      :desc  "Clojure programs, libs, and interviews"
      :url   "https://therepl.net"}

     {:label "Clojure Corner"
      :desc  "Interviews with devs and project maintainers"
      :url   "https://podcasts.apple.com/us/podcast/clojure-corner-by-flexiana/id1881543865"}

     {:label "Cognicast"
      :desc  "Clojure, Datomic, and software design"
      :url   "https://www.cognitect.com/cognicast"}

     {:label "The Eric Normand Podcast"
      :desc  "Functional programming ideas, patterns, and news"
      :url   "https://ericnormand.me/podcast"}

     {:label "Lost in Lambduhhs"
      :desc  "Functional programming and Clojure"
      :url   "https://open.spotify.com/show/34AisqmrBIXvlD0capu91P"}]}

   {:label "Video"
    :desc  "Video content, lecture talks, screencasts"
    :entries
    [{:label "Clojure TV"
      :desc  "Official channel"
      :url   "https://www.youtube.com/@ClojureTV"}

     {:label "Rich Hickey Talks"
      :desc  "Classic talks by the creator of Clojure"
      :url   "https://youtube.com/playlist?list=PLZdCLR02grLrEwKaZv-5QbUzK0zGKOOcr&si=D3tnwCraQLZHxlGi"}

     {:label "Apropos Clojure"
      :desc  "Interviews, panel discussions, and community livestreams"
      :url   "https://www.youtube.com/@aproposclojure6984"}

     {:label "Lambda Island"
      :desc  "Screencasts, tutorials, and conference presentations"
      :url   "https://www.youtube.com/@lambdaisland"}

     {:label "Practicalli"
      :desc  "Tutorials, livestreams, tooling, and development workflows"
      :url   "https://www.youtube.com/@Practicalli"}

     {:label "Dutch Clojure Days"
      :desc  "Conference recordings from Dutch Clojure Days"
      :url   "https://www.youtube.com/@DutchClojureDays"}

     {:label "Babashka Conf"
      :desc  "Conference talks focused on Babashka and tooling"
      :url   "https://www.youtube.com/@babashkatv"}

     {:label "ClojureD"
      :desc  "Conference talks from Germany's Clojure conference"
      :url   "https://www.youtube.com/@clojured"}

     {:label "EuroClojure"
      :desc  "Historic European conference talk archive"
      :url   "https://www.youtube.com/results?search_query=euroclojure"}]}

   {:label "Blogs"
    :desc  "Blogs, newsletters, and long-form writing"
    :entries
    [{:label "Planet Clojure"
      :desc  "Aggregator of community blog posts"
      :url   "http://planet.clojure.in"}

     {:label "Repl Adventures"
      :desc  "A blog mostly about Clojure and ClojureScript."
      :url   "https://blog.michielborkent.nl"}

     {:label "Meta Redux"
      :desc  "Emancs and Cider Development."
      :url   "https://metaredux.com/"}

     {:label "Clojure Goes Fast"
      :desc  "Performance engineering and optimization"
      :url   "https://clojure-goes-fast.com"}

     {:label "An Architect's View"
      :desc  "Guides, tutorials, and development workflows"
      :url   "https://corfield.org/"}

     {:label "from the shadows"
      :desc  "ClojureScript and shadow-cljs"
      :url   "https://code.thheller.com/"}

     {:label "iterate think thoughts"
      :desc  "Programming, Clojure, and software development"
      :url   "https://yogthos.net/archives.html"}

     {:label "tonsky.me"
      :desc  "Programming and UI Design"
      :url   "https://tonsky.me/"}

     {:label "Code with Kira"
      :desc  "The craft of building small, durable software"
      :url   "https://gigasquidsoftware.com/"}

     {:label "dissoc"
      :desc  "Clojure programming"
      :url   "https://blog.ambrosebs.com/"}

     {:label "Squid's Blog"
      :desc  "Clojure, Deep Learning, MXNet"
      :url   "https://gigasquidsoftware.com/"}

     {:label "Jank Blog"
      :desc  "A Clojure dialect that targets C++"
      :url   "https://jank-lang.org/blog/"}

     {:label "Practicalli"
      :desc  "Guides, tutorials, and development workflows"
      :url   "https://practical.li/blog"}

     {:label "Lambda Island"
      :desc  "Articles on Clojure, software design, and tooling"
      :url   "https://lambdaisland.com/blog"}

     {:label "Inside Clojure"
      :desc  "Official language and ecosystem updates"
      :url   "https://insideclojure.org"}

     {:label "Eric Normand"
      :desc  "Functional programming and Clojure essays"
      :url   "https://ericnormand.me/essays"}

     {:label "The REPL"
      :desc  "Newsletter covering Clojure projects and community news"
      :url   "https://therepl.net/newsletters"}

     {:label "Metosin"
      :desc  "Malli, Reitit, and production Clojure topics"
      :url   "https://www.metosin.fi/blog"}

     {:label "JUXT"
      :desc  "Architecture, systems, and Clojure engineering"
      :url   "https://www.juxt.pro/blog"}

     {:label "Flexiana"
      :desc  "Consulting, architecture, and Clojure ecosystem writing"
      :url   "https://flexiana.com/blog"}]}

   {:label   "Funding"
    :desc    "Funding Sources for Clojure OSS"
    :entries [{:label "Clojurists Together"
               :desc  "Non-profit that funds critical Clojure projects"
               :url   "https://clojuriststogether.org"}
              {:label "Nubank"
               :desc  "Open source developers sponsored by Nubank"
               :url   "https://github.com/orgs/nubank/sponsoring"}
              {:label "Thanks OSS Award"
               :desc  "Funding for Clojure OSS by Toyokumo"
               :url   "https://oss.toyokumo.co.jp/"}]}

   {:label   "Curated Lists"
    :desc    "Curated lists of books, tools, blogs, etc."
    :entries [{:label "Awesome Clojure"
               :desc  "Libraries, tools, and resources"
               :url   "https://github.com/razum2um/awesome-clojure"}
              {:label "Books"
               :desc  "Official list of Clojure books"
               :url   "https://clojure.org/community/books"}
              {:label "Data Science Tools and Libraries"
               :desc  "From the Scicloj community"
               :url   "https://scicloj.github.io/docs/resources/libs/"}
              {:label "Planet Clojure"
               :desc  "Aggregator of community blog posts"
               :url   "http://planet.clojure.in"}
              {:label "Excellent Clojure Learning Materials"
               :desc  "Opinionated curated resource collection"
               :url   "https://gist.github.com/ssrihari/0bf159afb781eef7cc552a1a0b17786f"}]}])
