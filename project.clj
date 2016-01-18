(defproject svtplay "0.1.0-SNAPSHOT"
  :description "SVTplay frontend for Samsung SmartTVs"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.omcljs/om "1.0.0-alpha28"]
                 [sablono "0.5.3"]
                 [facjure/mesh "0.4.0"]
                 [cljsjs/react-dom-server "0.14.3-0"]
                 [org.clojars.magomimmo/domina "2.0.0-SNAPSHOT"]
                 [funcool/cuerdas "0.7.1"]]
  :plugins [[lein-ancient "0.6.8"]
            [lein-cljsbuild "1.1.2"]
            [lein-figwheel "0.5.0-3"]
            [lein-garden "0.2.6"]
            [lein-pdo "0.1.1"]]
  :resource-paths ["resources"]
  :cljsbuild {:builds
              [{:id "dev"
                :figwheel true
                :source-paths ["src/svtplay"]
                :compiler
                {:main svtplay.core
                 :asset-path "js"
                 :output-to "resources/public/js/main.js"
                 :output-dir "resources/public/js"
                 :parallel-build true
                 :verbose true}}
               {:id "prod"
                :source-paths ["src/svtplay"]
                :compiler
                {:main svtplay.core
                 :asset-path "js"
                 :output-to "resources/public/js/main.min.js"
                 :parallel-build true
                 :optimizations :advanced
                 :verbose false}}]}
  :garden {:builds
           [{:id "design"
             :source-paths ["src" "svtplay"]
             :stylesheet svtplay.styles/index
             :compiler {:output-to "resources/public/css/svtplay.css"
                        :pretty-print? true}}
            {:id "prod"
             :source-paths ["src" "svtplay"]
             :stylesheet svtplay.styles/index
             :compiler {:output-to "resources/public/css/svtplay.min.css"
                        :pretty-print? false}}]}
  :figwheel {:css-dirs ["resources/public/css"]}
  :aliases {"dev" ["pdo" "garden" "auto" "design," "figwheel"]
            "release" ["pdo" "prod"]})
