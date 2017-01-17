(ns server.echo
  (:require [net.tcp         :as tcp]
            [net.ty.channel  :as channel]
            [net.ty.pipeline :as pipeline]))

(defn pipeline
  []
  (pipeline/channel-initializer
   [(pipeline/line-based-frame-decoder)
    pipeline/string-decoder
    pipeline/string-encoder
    pipeline/line-frame-encoder
    (pipeline/with-input [ctx msg]
      (channel/write-and-flush! ctx msg))]))

(defn -main
  [& _]
  (tcp/server {:handler (pipeline)} "localhost" 1337))
