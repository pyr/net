(ns net.tcp
  (:require [net.ty.bootstrap :as bootstrap]
            [net.ty.channel   :as channel]))


(defn server
  [bootstrap-config host port]
  (let [bs  (bootstrap/server-bootstrap bootstrap-config)
        srv (bootstrap/bind! bs host port)]
    (fn []
      (-> server channel/channel channel/close! channel/sync-uninterruptibly!))))
