(ns net.ty.channel
  (:refer-clojure :exclude [await])
  (:import io.netty.channel.ChannelFuture
           io.netty.channel.Channel
           io.netty.channel.group.ChannelGroup
           io.netty.channel.group.DefaultChannelGroup
           io.netty.util.concurrent.GlobalEventExecutor))

(defn ^Channel channel
  [channel-holder]
  (.channel channel-holder))

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

(defn channel-group
  [name]
  (DefaultChannelGroup. name GlobalEventExecutor/INSTANCE))

(defn add-to-group
  [group chan]
  (.add group chan))

(defn remove-from-group
  [group chan]
  (.remove group chan))

(defn close!
  [chan]
  (.close chan))
