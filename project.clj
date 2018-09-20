(defproject net.ignorare/haus "0.1.0-SNAPSHOT"
  :description "Haus Accounts (and friends)"
  :url "https://bitbucket.org/psagers/haus/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [clj-time "0.14.4"]
                 [com.fzakaria/slf4j-timbre "0.3.12" :exclusions [com.taoensso/timbre]]
                 [com.github.java-json-tools/json-schema-validator "2.2.10"]
                 [com.impossibl.pgjdbc-ng/pgjdbc-ng "0.7"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.taoensso/truss "1.5.0"]
                 [compojure "1.6.1" :exclusions [ring/ring-codec]]
                 [failjure "1.3.0"]
                 [migratus "1.0.8"]
                 [org.clojure/core.match "0.2.2"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [ring/ring-core "1.6.3"]
                 [ring/ring-spec "0.0.4"]
                 [slingshot "0.12.2"]]
  :plugins [[lein-ring "0.12.4"]]
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[ring/ring-mock "0.3.2"]
                                  [ring/ring-devel "1.6.3"]
                                  [circleci/circleci.test "0.4.1"]
                                  [org.clojure/test.check "0.9.0"]]
                   :repl-options {:init (do (require '[taoensso.timbre])
                                            (taoensso.timbre/set-level! :info))}}
             :warn {:global-vars {*warn-on-reflection* true}}
             :uberjar {:aot :all}}
  :ring {:init haus.web/init
         :handler haus.web/handler}
  :aliases {"db" ["run" "-m" "haus.db"]
            "test" ["run" "-m" "circleci.test/dir" :project/test-paths]
            "tests" ["run" "-m" "circleci.test"]
            "retest" ["run" "-m" "circleci.test.retest"]})
