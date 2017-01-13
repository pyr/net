(defproject spootnik/net "0.3.1"
  :description "the clojure netty companion"
  :url "https://github.com/pyr/net"
  :license {:name "MIT License"}
  :profiles {:dev {:resource-paths ["test/resources"]
                   :source-paths ["examples"]
                   :main webfile.server
                   :dependencies [[org.slf4j/slf4j-api        "1.7.22"]
                                  [org.slf4j/slf4j-log4j12    "1.7.22"]
                                  [org.clojure/test.check     "0.9.0"]
                                  [com.stuartsierra/component "0.3.1"]]}}
  :dependencies [[org.clojure/clojure       "1.9.0-alpha14"]
                 [org.clojure/core.async    "0.2.395"]
                 [org.clojure/tools.logging "0.3.1"]
                 [io.netty/netty-all        "4.1.7.Final"]])
