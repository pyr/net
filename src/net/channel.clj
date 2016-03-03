(ns net.channel
  (:import netty.channel.ChannelFuture
           netty.channel.Channel))

(defn ^Channel channel
  [^ChannelFuture channel-future]
  (.channel channel-future))

(defn await
  [^Channel channel]
  (.await channel))
