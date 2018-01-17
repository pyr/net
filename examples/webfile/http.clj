(ns webfile.http
  (:require [com.stuartsierra.component :as component]
            [net.http.server            :as http]
            [webfile.engine             :as engine]))

(defn dispatch
  [engine {:keys [request-method body headers uri] :as request}]
  (prn {:uri uri :request-method request-method :headers headers})
  (doto (engine/handle-operation engine request-method uri body)
    (partial prn :response)))

(defrecord HttpServer [server engine]
  component/Lifecycle
  (start [this]
    (let [http-opts  {:port 8000}
          handler-fn (partial dispatch engine)
          server     (http/run-server http-opts handler-fn)]
      (assoc this :server server)))
  (stop [this]
    (when server
      (server))
    (assoc this :server nil)))

(defn make-http
  []
  (map->HttpServer {}))
