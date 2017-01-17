(ns server.chat
  (:require [net.ty.channel  :as channel]
            [net.ty.pipeline :as pipeline]
            [net.tcp         :as tcp]))

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

(defn pipeline
  []
  (let [group (channel/channel-group "clients")]
    (pipeline/channel-initializer
     [(pipeline/line-based-frame-decoder)
      pipeline/string-decoder
      pipeline/string-encoder
      pipeline/line-frame-encoder
      (pipeline/read-timeout-handler 60)
      (pipeline/make-handler-adapter (chat-adapter group))])))

(defn -main [& args]
  (tcp/server {:handler (pipeline)} "localhost" 1337))
