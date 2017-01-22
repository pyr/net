(ns net.ty.channel
  "Handy functions to deal with netty channels"
  (:refer-clojure :exclude [await])
  (:import io.netty.channel.ChannelFuture
           io.netty.channel.ChannelFutureListener
           io.netty.channel.Channel
           io.netty.channel.ChannelHandlerContext
           io.netty.channel.group.ChannelGroup
           io.netty.channel.group.DefaultChannelGroup
           io.netty.util.concurrent.GlobalEventExecutor))

(defn await
  "Wait on a channel"
  [^ChannelFuture channel]
  (.await channel))

(defmulti ^Channel channel type)

(defmethod channel ChannelHandlerContext
  [channel-holder]
  (.channel channel-holder))

(defmethod channel AbstractBootstrap$PendingRegistrationPromise
  [channel-holder]
  (.channel (await channel-holder)))

(defn write!
  "write a payload to a channel"
  [^Channel channel msg]
  (.write channel msg))

(defn flush!
  "flush a channel"
  [^Channel channel]
  (.flush channel))

(defn write-and-flush!
  "write a payload, then flush a channel"
  [^Channel channel msg]
  (.writeAndFlush channel msg))

(defn channel-group
  "Create a named channel group"
  [name]
  (DefaultChannelGroup. ^String name
                        GlobalEventExecutor/INSTANCE))

(defn add-to-group
  "Add a channel to a channel group"
  [^ChannelGroup group
   ^Channel chan]
  (.add group chan))

(defn remove-from-group
  "Remove a channel from a channel group"
  [^ChannelGroup group
   ^Channel chan]
  (.remove group chan))

(defn close!
  "Close a channel"
  [^Channel chan]
  (.close chan))

(defn sync-uninterruptibly!
  "Sync a channel, without interruptions"
  [^ChannelFuture chan]
  (.syncUninterruptibly chan))

(defn ^ChannelFuture close-future
  "Get the close future for a channel"
  [^Channel chan]
  (.closeFuture chan))
