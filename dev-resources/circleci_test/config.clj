{:global-fixture (fn [f]
                   (taoensso.timbre/set-level! :warn)
                   (haus.test.util/with-db f))}
