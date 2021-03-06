(ns webfile.http
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging      :refer [debug error]]
            [clojure.core.async         :as a]
            [net.http.server            :as http]
            [webfile.engine             :as engine]))

(defn str->num
  [^String x]
  (try (Long/parseLong x) (catch Exception _)))

(defn dispatch
  [engine {:keys [request-method body headers uri] :as request}]
  (debug "request" (select-keys request http/request-data-keys))
  (cond
    (= request-method :error)
    (error (:error request) "cannot continue")

    (if-let [len (some-> headers :content-length str->num)] (neg? len))
    (do
      (a/close! body)
      {:status 400 :headers {:connection "close"}})

    :else
    (engine/handle-operation engine request-method uri body)))

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
