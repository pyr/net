(ns net.ty.channel
  "Handy functions to deal with netty channels"
  (:refer-clojure :exclude [await])
  (:import io.netty.channel.ChannelFuture
           io.netty.channel.ChannelFutureListener
           io.netty.channel.Channel
           io.netty.channel.group.ChannelGroup
           io.netty.channel.group.DefaultChannelGroup
           io.netty.util.concurrent.GlobalEventExecutor))

(defn ^Channel channel
  "Extract the channel from a channel holder"
  [channel-holder]
  (.channel channel-holder))

(defn await
  "Wait on a channel"
  [^Channel channel]
  (.await channel))

(defn write!
  "write a payload to a channel"
  [channel msg]
  (.write channel msg))

(defn flush!
  "flush a channel"
  [channel]
  (.flush channel))

(defn write-and-flush!
  "write a payload, then flush a channel"
  [channel msg]
  (.writeAndFlush channel msg))

(defn channel-group
  "Create a named channel group"
  [name]
  (DefaultChannelGroup. name GlobalEventExecutor/INSTANCE))

(defn add-to-group
  "Add a channel to a channel group"
  [group chan]
  (.add group chan))

(defn remove-from-group
  "Remove a channel from a channel group"
  [group chan]
  (.remove group chan))

(defn close!
  "Close a channel"
  [chan]
  (.close chan))

(defn sync-uninterruptibly!
  "Sync a channel, without interruptions"
  [chan]
  (.syncUninterruptibly chan))

(defn close-future
  "Get the close future for a channel"
  [chan]
  (.closeFuture chan))
