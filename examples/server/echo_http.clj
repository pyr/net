(ns server.echo-http
  (:require [net.http.server       :as http]
            [clojure.core.async    :as a]
            [clojure.tools.logging :refer [info]]))

(defn ->port
  [^String s]
  (try
    (Long/parseLong s)
    (catch Exception _)))

(defn echo-handler
  [request]
  {:status  200
   :headers {:connection "close"}
   :body    (a/pipe (:body request) (a/chan 10))})

(defn -main
  [& [sport]]
  (let [port (or (->port sport) 8080)]
    (http/run-server {:port port} echo-handler)
    (info "server running on port" port)))
