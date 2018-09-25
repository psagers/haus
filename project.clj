(defproject net.ignorare/haus "0.1.0-SNAPSHOT"
  :description "Haus Accounts (and friends)"
  :url "https://bitbucket.org/psagers/haus/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.match "0.3.0-alpha5"]
                 [org.clojure/data.json "0.2.6"]

                 ; Component
                 [com.stuartsierra/component "0.3.2"]

                 ; Logging junk
                 [com.fzakaria/slf4j-timbre "0.3.12"]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]

                 ; Database
                 [org.clojure/java.jdbc "0.7.8"]
                 [com.impossibl.pgjdbc-ng/pgjdbc-ng "0.7"]
                 [com.mchange/c3p0 "0.9.5.2"]
                 [migratus "1.0.8"]
                 [clj-time "0.14.4"]

                 ; HTTP
                 [io.pedestal/pedestal.service "0.5.4"]
                 [io.pedestal/pedestal.jetty "0.5.4"]
                 [ring/ring-spec "0.0.4"]

                 ; Misc
                 [com.taoensso/truss "1.5.0"]
                 [slingshot "0.12.2"]
                 [failjure "1.3.0"]]

  :profiles {:dev {:source-paths ["src" "dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.11"]
                                  [org.clojure/test.check "0.9.0"]
                                  [io.pedestal/pedestal.service-tools "0.5.4" :exclusions [ch.qos.logback/logback-classic]]
                                  [circleci/circleci.test "0.4.1" :exclusions [org.clojure/data.xml]]]
                   :repl-options {:init-ns user}}
             :warn {:global-vars {*warn-on-reflection* true}}
             :uberjar {:aot haus.main}}

  :aliases {"db" ["run" "-m" "haus.db"]
            "test" ["run" "-m" "circleci.test/dir" :project/test-paths]
            "tests" ["run" "-m" "circleci.test"]
            "retest" ["run" "-m" "circleci.test.retest"]}

  :main ^{:skip-aot true} haus.main)
