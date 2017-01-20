(ns net.ty.future
  "Handy functions to deal with netty futures"
  (:import (io.netty.channel ChannelFutureListener)
           (io.netty.channel ChannelFuture)))

(defn success?
  "Predicate to test for future success"
  [^ChannelFuture f]
  (.isSuccess f))

(defn cancelled?
  "Predicate to test for future cancellation"
  [^ChannelFuture f]
  (.isCancelled f))

(defn incomplete?
  "Predicate to test for future cancellation or failure"
  [^ChannelFuture f]
  (or (cancelled? f) (not (success? f))))

(defn complete?
  "Predicate to test for future completeness"
  [^ChannelFuture f]
  (and (success? f) (not (cancelled? f))))

(defmacro with-result
  "Marco to create future listeners"
  [[future action] & body]
  `(.addListener
    ~action
    (reify ChannelFutureListener
      (operationComplete [this# ~future]
        (do ~@body)))))

(defn operation-complete
  "Signal that operation completed on a listener"
  ([^ChannelFutureListener listener]
   (.operationComplete listener nil))
  ([^ChannelFutureListener listener future]
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
  [^ChannelFuture future]
  (.sync future))

(defn add-listener
  "Add a listener to a channel"
  [^ChannelFuture chan ^ChannelFutureListener listener]
  (.addListener chan listener))

(def close-listener
  "Listener which closes a channel"
  io.netty.channel.ChannelFutureListener/CLOSE)

(defn add-close-listener
  "Add close-listener to a channel"
  [^ChannelFuture chan]
  (.addListener chan io.netty.channel.ChannelFutureListener/CLOSE))
