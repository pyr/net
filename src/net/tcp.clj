(ns net.tcp
  "Functions for simple creation of TCP servers and clients"
  (:require [net.ty.bootstrap :as bootstrap]
            [net.ty.channel   :as channel]))

(defn client
  "Build a TCP client and attempt connection from a host,
   port, and optional bootstrap configuration."
  ([host port]
   (client host port {}))
  ([host port bootstrap-config]
   (let [bs (bootstrap/bootstrap bootstrap-config)]
     (-> (bootstrap/remote-address! bs host port)
         (bootstrap/connect!)))))

(defn server
  "Create a server handler from a bootstrap configuration, host, and port.
   Yields a function of no args which will gracefully close the server."
  [bootstrap-config host port]
  (let [bs  (bootstrap/server-bootstrap bootstrap-config)
        srv (bootstrap/bind! bs host port)]
    (bootstrap/shutdown-fn (channel/channel srv) (.childGroup bs))))
