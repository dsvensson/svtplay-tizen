(defproject svtplay "0.1.0-SNAPSHOT"
  :description "SVTplay frontend for Samsung SmartTVs"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.omcljs/om "1.0.0-alpha28"]
                 [sablono "0.5.3"]
                 [garden "1.3.0"]
                 [cljsjs/react-dom-server "0.14.3-0"]
                 [org.clojars.magomimmo/domina "2.0.0-SNAPSHOT"]
                 [funcool/cuerdas "0.7.1"]]
  :plugins [[lein-ancient "0.6.8"]
            [lein-cljsbuild "1.1.2"]
            [lein-figwheel "0.5.0-3"]]
  :resource-paths ["resources"]
  :cljsbuild
  {:builds
   [{:id "dev"
     :figwheel true
     :source-paths ["src/svtplay"]
     :compiler
     {:main svtplay.core
      :asset-path "js"
      :output-to "resources/public/js/main.js"
      :output-dir "resources/public/js"
      :recompile-dependents true
      :parallel-build true
      :verbose true}}
    {:id "prod"
     :source-paths ["src/svtplay"]
     :compiler
     {:main svtplay.core
      :asset-path "js"
      :output-to "build/main.js"
      :recompile-dependents true
      :parallel-build true
      :optimizations :advanced
      :verbose false}}]}
  :figwheel {:css-dirs ["resources/public/css"]})
