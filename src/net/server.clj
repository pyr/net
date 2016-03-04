(ns net.server
  (:require [net.ty.bootstrap :as bootstrap]
            [net.ty.channel   :as channel]
            [net.ty.pipeline  :as pipeline]))

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
      (channel/write-and-flush! ctx "welcome dear client"))
    (channel-read [this ctx msg]
      (if (= msg "quit")
        (do (channel/write-and-flush! ctx "bye!")
            (channel/remove-from-group channel-group (channel/channel ctx))
            (channel/close! ctx))
        (let [src (channel/channel ctx)]
          (doseq [dst channel-group :when (not= dst src)]
            (channel/write-and-flush! dst msg)))))))

(def pipeline
  (let [group (channel/channel-group "clients")]
    [(pipeline/line-based-frame-decoder)
     pipeline/string-decoder
     pipeline/string-encoder
     pipeline/line-frame-encoder
     (pipeline/read-timeout-handler 60)
     (pipeline/make-handler-adapter (chat-adapter group))]))

(def bootstrap
  {:child-options {:so-keepalive           true
                   :so-backlog             128
                   :connect-timeout-millis 1000}
   :handler       (pipeline/channel-initializer pipeline)})
(comment

  (def bound
    (.bind (bootstrap/server-bootstrap bootstrap) "127.0.0.1" (int 6379)))
  (def chan
    (.channel bound))
  (-> chan .close .syncUninterruptibly)
  )
