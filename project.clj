(defproject haus "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.fzakaria/slf4j-timbre "0.3.12"]
                 [com.impossibl.pgjdbc-ng/pgjdbc-ng "0.7"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [migratus "1.0.8"]
                 [ring/ring-core "1.6.3"]]
  :plugins [[lein-ring "0.12.4"]]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :ring {:handler net.ignorare.haus.web/handler}
  :aliases {"db" ["run" "-m" "net.ignorare.haus.core.db"]})
