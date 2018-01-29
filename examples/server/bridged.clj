(ns server.bridged
  (:require [net.tcp            :as tcp]
            [net.ty.channel     :as channel]
            [net.ty.pipeline    :as pipeline]
            [clojure.core.async :as a]))

(defn dumb-handler
  [ch]
  (a/go-loop []
    (if-let [msg (a/<! ch)]
      (do
        (println "received message:" msg)
        (recur))
      (println "all done"))))

(defn bridged-handler
  [f]
  (let [bc (volatile! nil)]
    (reify pipeline/HandlerAdapter pipeline/ChannelActive pipeline/IsSharable pipeline/ChannelInactive
      (is-sharable? [this]
        false)
      (channel-read [this ctx msg]
        (channel/offer-val @bc msg))
      (channel-active [this ctx]
        (println "opening")
        (f (vreset! bc (channel/read-channel ctx 10))))
      (channel-inactive [thix ctx]
        (println "closing")
        (a/close! @bc)))))

(defn pipeline
  []
  (pipeline/channel-initializer
   [(pipeline/line-based-frame-decoder)
    pipeline/string-decoder
    pipeline/string-encoder
    pipeline/line-frame-encoder
    #(pipeline/make-handler-adapter
      (bridged-handler dumb-handler))]))

(defn -main
  [& _]
  (tcp/server {:handler (pipeline) :child-options {:auto-read false}}
              "localhost"
              1337)
  (println "server ready on port 1337"))
