(ns server.http
  (:require [net.http.server :as http]))

(defn handler
  [request]
  {:status  200
   :headers {"Content-Type"   "text/plain"
             "Content-Length" "6"}
   :body    "hello\n"})

(defn -main
  [& _]
  (http/run-server {:ring-handler handler}))
