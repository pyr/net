(ns net.ty.future)

(defn success?
  [^io.netty.channel.ChannelFuture f]
  (.isSuccess f))

(defn cancelled?
  [^io.netty.channel.ChannelFuture f]
  (.isCancelled f))

(defn incomplete?
  [^io.netty.channel.ChannelFuture f]
  (or (cancelled? f) (not (success? f))))

(defn complete?
  [^io.netty.channel.ChannelFuture f]
  (and (success? f) (not (cancelled? f))))

(defmacro with-result
  [[future action] & body]
  `(.addListener
    ~action
    (reify io.netty.channel.ChannelFutureListener
      (operationComplete [this# ~future]
        (do ~@body)))))
