(ns net.server
  (:require [net.ty.bootstrap :as bootstrap]
            [net.ty.channel   :as channel]
            [net.ty.pipeline  :as pipeline]))

(defn chat-adapter
  []
  (reify
    pipeline/HandlerAdapter
    (is-sharable? [this]
      true)
    (capabilities [this]
      #{:channel-active :channel-read})
    (channel-active [this ctx]
      (channel/write-and-flush! ctx "welcome dear client\r\n"))
    (channel-read [this ctx msg]
      (channel/write-and-flush! ctx "ok, thanks!\r\n"))))

(def pipeline
  [(pipeline/line-based-frame-decoder)
   pipeline/string-decoder
   pipeline/string-encoder
   (pipeline/read-timeout-handler 1)
   (pipeline/make-handler-adapter (chat-adapter))])

(def bootstrap
  {:child-options {:so-keepalive           true
                   :so-backlog             128
                   :connect-timeout-millis 1000}
   :handler       (pipeline/channel-initializer pipeline)})


(comment

  (.bind (bootstrap/server-bootstrap bootstrap) "127.0.0.1" (int 6379))

  (def c (.channel *1))

  (-> c .close .syncUninterruptibly)
  )
