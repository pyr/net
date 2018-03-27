(defproject spootnik/net "0.3.3-beta37"
  :description "A clojure netty companion"
  :url "https://github.com/pyr/net"
  :license {:name "MIT License"
            :url  "https://github.com/pyr/net/tree/master/LICENSE"}
  :plugins [[lein-codox   "0.10.2"]
            [lein-ancient "0.6.15"]]
  :codox {:source-uri  "https://github.com/pyr/net/blob/{version}/{filepath}#L{line}"
          :output-path "docs"
          :namespaces  [#"^net"]
          :doc-files   ["examples/guides/intro.md"
                        "examples/guides/echo.md"
                        "examples/guides/chat.md"
                        "examples/guides/tls.md"
                        "examples/guides/http-server.md"]
          :metadata    {:doc/format :markdown}}
  :profiles {:dev {:resource-paths ["test/resources"]
                   :source-paths   ["examples"]
                   :main           webfile.server
                   :dependencies   [[org.slf4j/slf4j-api        "1.7.25"]
                                    [org.slf4j/slf4j-log4j12    "1.7.25"]
                                    [org.clojure/tools.logging  "0.4.0"]
                                    [com.stuartsierra/component "0.3.2"]]
                   :jvm-opts       ["-Dio.netty.leakDetection.level=paranoid"]}}
  :dependencies [[org.clojure/clojure                      "1.9.0"]
                 [org.clojure/core.async                   "0.4.474"]
                 [io.netty/netty-all                       "4.1.22.Final"]
                 [io.netty/netty-tcnative                  "2.0.7.Final"]
                 [io.netty/netty-tcnative-boringssl-static "2.0.7.Final"]]
  :global-vars {*warn-on-reflection* true})
