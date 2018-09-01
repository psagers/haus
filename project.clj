(defproject net.ignorare/haus "0.1.0-SNAPSHOT"
  :description "Haus Accounts (and friends)"
  :url "https://bitbucket.org/psagers/haus/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [slingshot "0.12.2"]
                 [com.github.java-json-tools/json-schema-validator "2.2.10"]
                 [com.taoensso/truss "1.5.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.fzakaria/slf4j-timbre "0.3.12" :exclusions [com.taoensso/timbre]]
                 [com.impossibl.pgjdbc-ng/pgjdbc-ng "0.7"]
                 [migratus "1.0.8"]
                 [ring/ring-core "1.6.3"]
                 [ring/ring-json "0.4.0"]
                 [compojure "1.6.1" :exclusions [ring/ring-codec]]]
  :plugins [[lein-ring "0.12.4"]]
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[ring/ring-mock "0.3.2" :exclusions [cheshire]]
                                  [ring/ring-devel "1.6.3"]
                                  [circleci/circleci.test "0.4.1"]]}
             :warn {:global-vars {*warn-on-reflection* true}}
             :uberjar {:aot :all}}
  :ring {:init haus.web/init
         :handler haus.web/handler}
  :aliases {"db" ["run" "-m" "haus.db.migrate"]
            "test" ["run" "-m" "circleci.test/dir" :project/test-paths]
            "tests" ["run" "-m" "circleci.test"]
            "retest" ["run" "-m" "circleci.test.retest"]})
