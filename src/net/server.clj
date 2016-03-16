(ns net.server
  (:require [net.ty.bootstrap :as bootstrap]
            [net.ty.channel   :as channel]
            [net.ty.pipeline  :as pipeline]
            [net.ssl          :as ssl]))

(defn chat-adapter
  [channel-group]
  (reify
    pipeline/HandlerAdapter
    (is-sharable? [this]
      true)
    (capabilities [this]
      #{:channel-active :channel-read})
    (channel-active [this ctx]
      (channel/add-to-group channel-group (channel/channel ctx))
      (channel/write-and-flush! ctx "welcome to the chat"))
    (channel-read [this ctx msg]
      (if (= msg "quit")
        (do (channel/write-and-flush! ctx "bye!")
            (channel/close! ctx))
        (let [src (channel/channel ctx)]
          (doseq [dst channel-group :when (not= dst src)]
            (channel/write-and-flush! dst msg)))))))

(def pipeline
  (let [group   (channel/channel-group "clients")
        ->path  #(str "/home/pyr/.ssh/certs/" %)
        ssl-ctx (ssl/server-context
                 {:pkey      (->path "pyr.pkey64.p8")
                  :cert      (->path "pyr.exoscale.local.pem")
                  :ca-cert   (->path "ca_crt.pem")
                  :storage   :file
                  :auth-mode :auth-mode-require})]
    [(ssl/handler-fn ssl-ctx)
     (pipeline/line-based-frame-decoder)
     pipeline/string-decoder
     pipeline/string-encoder
     pipeline/line-frame-encoder
     (pipeline/read-timeout-handler 60)
     (pipeline/make-handler-adapter (chat-adapter group))]))

(def bootstrap
  {:child-options {:so-keepalive           true
;;                   :so-backlog             128
                   :connect-timeout-millis 1000}
   :handler       (pipeline/channel-initializer pipeline)})


(comment
  (def  server (bind! (bootstrap/server-bootstrap bootstrap) "localhost" 6379))
  (defn close []
    (-> server channel/channel channel/close! channel/sync-uninterruptibly!)))
