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

(defn operation-complete
  ([listener]
   (.operationComplete listener nil))
  ([listener future]
   (.operationComplete listener future)))

(defmacro deflistener
  [sym [listener future bindings] & body]
  `(defn ~sym
     ~bindings
     (reify
       io.netty.channel.ChannelFutureListener
       (operationComplete [~listener ~future]
         (do ~@body)))))

(defn sync!
  [future]
  (.sync future))


(defn add-listener
  [chan listener]
  (.addListener chan listener))

(def close-listener
  ""
  io.netty.channel.ChannelFutureListener/CLOSE)

(defn add-close-listener
  [chan]
  (.addListener chan io.netty.channel.ChannelFutureListener/CLOSE))
