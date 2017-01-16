(ns net.ty.future
  "Handy functions to deal with netty futures")

(defn success?
  "Predicate to test for future success"
  [^io.netty.channel.ChannelFuture f]
  (.isSuccess f))

(defn cancelled?
  "Predicate to test for future cancellation"
  [^io.netty.channel.ChannelFuture f]
  (.isCancelled f))

(defn incomplete?
  "Predicate to test for future cancellation or failure"
  [^io.netty.channel.ChannelFuture f]
  (or (cancelled? f) (not (success? f))))

(defn complete?
  "Predicate to test for future completeness"
  [^io.netty.channel.ChannelFuture f]
  (and (success? f) (not (cancelled? f))))

(defmacro with-result
  "Marco to create future listeners"
  [[future action] & body]
  `(.addListener
    ~action
    (reify io.netty.channel.ChannelFutureListener
      (operationComplete [this# ~future]
        (do ~@body)))))

(defn operation-complete
  "Signal that operation completed on a listener"
  ([listener]
   (.operationComplete listener nil))
  ([listener future]
   (.operationComplete listener future)))

(defmacro deflistener
  "Define a channel future listener"
  [sym [listener future bindings] & body]
  `(defn ~sym
     ~bindings
     (reify
       io.netty.channel.ChannelFutureListener
       (operationComplete [~listener ~future]
         (do ~@body)))))

(defn sync!
  "Synchronize future"
  [future]
  (.sync future))

(defn add-listener
  "Add a listener to a channel"
  [chan listener]
  (.addListener chan listener))

(def close-listener
  "Listener which closes a channel"
  io.netty.channel.ChannelFutureListener/CLOSE)

(defn add-close-listener
  "Add close-listener to a channel"
  [chan]
  (.addListener chan io.netty.channel.ChannelFutureListener/CLOSE))
