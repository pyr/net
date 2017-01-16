(ns webfile.http
  (:require [com.stuartsierra.component :as component]
            [net.http.server            :as http]
            [webfile.engine             :as engine]))

(defn dispatch
  [engine {:keys [request-method body uri] :as request}]
  (engine/handle-operation engine request-method uri body))

(defrecord HttpServer [server engine chunk-size]
  component/Lifecycle
  (start [this]
    (let [http-opts  {:port             8000
                      :aggregate-length 0
                      :chunk-size       chunk-size}
          handler-fn (partial dispatch engine)
          server     (http/run-server http-opts handler-fn)]
      (assoc this :server server)))
  (stop [this]
    (when server
      (server))
    (assoc this :server nil)))

(defn make-http
  [chunk-size]
  (map->HttpServer {:chunk-size chunk-size}))
