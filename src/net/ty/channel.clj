(ns net.ty.channel
  (:refer-clojure :exclude [await])
  (:import io.netty.channel.ChannelFuture
           io.netty.channel.Channel))

(defn ^Channel channel
  [^ChannelFuture channel-future]
  (.channel channel-future))

(defn await
  [^Channel channel]
  (.await channel))

(defn write!
  [channel msg]
  (.write channel msg))

(defn flush!
  [channel]
  (.flush channel))

(defn write-and-flush!
  [channel msg]
  (.writeAndFlush channel msg))
