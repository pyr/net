(ns net.ty.channel
  "Handy functions to deal with netty channels"
  (:refer-clojure :exclude [await])
  (:import io.netty.channel.ChannelFuture
           io.netty.channel.ChannelFutureListener
           io.netty.channel.Channel
           io.netty.channel.ChannelHandlerContext
           io.netty.channel.group.ChannelGroup
           io.netty.channel.group.DefaultChannelGroup
           io.netty.util.concurrent.GlobalEventExecutor
           io.netty.bootstrap.AbstractBootstrap$PendingRegistrationPromise))

(defn await
  "Wait on a channel"
  [^ChannelFuture channel]
  (.await channel))

(defn ^Channel channel
  "Extract the channel from a channel holder"
  [^AbstractBootstrap$PendingRegistrationPromise channel-holder]
  (.channel channel-holder))

(defprotocol ChannelResource
  (write! [this msg] "write a payload to resource")
  (write-and-flush! [this msg] "write a payload, then flush a resource")
  (flush! [this] "Request to flush all pending messages")
  (close! [this] "Request to close resource")
  (disconnect! [this])
  (deregister! [this]))

(extend-type ChannelHandlerContext
  ChannelResource
  (write! [ch msg]
    (.write ch msg))

  (write-and-flush! [ch msg]
    (.writeAndFlush ch msg))

  (flush! [ch]
    (.flush ch))

  (close! [ch]
    (.close ch))

  (disconnect! [ch]
    (.disconnect ch))

  (deregister! [ch]
    (.deregister ch)))

(extend-type Channel
  ChannelResource
  (write! [ch msg]
    (.write ch msg))

  (write-and-flush! [ch msg]
    (.writeAndFlush ch msg))

  (flush! [ch]
    (.flush ch))

  (close! [ch]
    (.close ch))

  (disconnect! [ch]
    (.disconnect ch))

  (deregister! [ch]
    (.deregister ch)))

(extend-type ChannelGroup
  ChannelResource
  (write! [ch-g msg]
    (.write ch-g msg))

  (write-and-flush! [ch-g msg]
    (.writeAndFlush ch-g msg))

  (flush! [ch-g]
    (.flush ch-g))

  (close! [ch]
    (.close ch))

  (disconnect! [ch]
    (.disconnect ch))

  (deregister! [ch]
    (.deregister ch)))

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

(defn sync-uninterruptibly!
  "Sync a channel, without interruptions"
  [^ChannelFuture chan]
  (.syncUninterruptibly chan))

(defn ^ChannelFuture close-future
  "Get the close future for a channel"
  [^Channel chan]
  (.closeFuture chan))
