(ns net.ty.channel
  "Handy functions to deal with netty channels"
  (:refer-clojure :exclude [await])
  (:import io.netty.channel.ChannelFuture
           io.netty.channel.ChannelFutureListener
           io.netty.channel.Channel
           io.netty.channel.ChannelHandlerContext
           io.netty.channel.DefaultChannelPromise
           io.netty.channel.group.ChannelGroup
           io.netty.channel.group.DefaultChannelGroup
           io.netty.util.AttributeKey
           io.netty.util.Attribute
           io.netty.util.concurrent.GlobalEventExecutor
           io.netty.bootstrap.AbstractBootstrap$PendingRegistrationPromise))

(defn await!
  "Wait on a channel"
  [^ChannelFuture channel]
  (.await channel))

(defn read!
  "Schedule read on a channel, preferably one with setAutoRead set to false"
  [^Channel channel]
  (.read channel))

(defn active?
  [^Channel channel]
  (.isActive channel))

(defprotocol ChannelHolder
  (get-channel [this] "Extract channel from holder"))

(defprotocol ChannelResource
  (write! [this msg] "write a payload to resource")
  (write-and-flush! [this msg] "write a payload, then flush a resource")
  (flush! [this] "Request to flush all pending messages")
  (close! [this] "Request to close resource")
  (disconnect! [this])
  (deregister! [this]))

(defn ^Channel channel
  [x]
  (get-channel x))

(extend-type AbstractBootstrap$PendingRegistrationPromise
  ChannelHolder
  (get-channel [this]
    (.channel this)))

(extend-type ChannelHandlerContext
  ChannelHolder
  (get-channel [this]
    (.channel this))
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

(extend-type DefaultChannelPromise
  ChannelHolder
  (get-channel [this]
    (.channel this)))

(extend-type Channel
  ChannelHolder
  (get-channel [this]
    this)
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
    (.disconnect ch)))

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

(defn get-attr-key
  [k]
  (let [sk (str k)]
    (if (AttributeKey/exists sk)
      (AttributeKey/valueOf sk)
      (AttributeKey/newInstance sk))))

(defn ^Attribute get-attr
  [^Channel chan k]
  (.attr chan (get-attr-key k)))

(defn set-attr!
  [^Channel chan k v]
  (let [attr (get-attr chan k)]
    (.set attr v)))

(defn compare-and-set-attr!
  [^Channel chan k prev next]
  (let [attr (get-attr chan k)]
    (.compareAndSet attr prev next)))

(defn get-and-set-attr!
  [^Channel chan k v]
  (let [attr (get-attr chan k)]
    (.getAndSet attr v)))

(defn set-attr-if-absent!
  [^Channel chan k v]
  (let [attr (get-attr chan k)]
    (.setIfAbsent attr v)))

(defn get-attr-value
  [^Channel chan k]
  (let [attr (get-attr chan k)]
    (.get attr)))

(defn get-and-remove-attr!
  [^Channel chan k]
  (let [attr (get-attr chan k)
        val   (.get attr)]
    (.set attr nil)
    val))

(defn remove-attr!
  [^Channel chan k]
  (let [attr (get-attr chan k)]
    (.set attr nil)))
