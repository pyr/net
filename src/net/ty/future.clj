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

(defn add-listener
  "Add a listener to a channel"
  [^ChannelFuture chan ^ChannelFutureListener listener]
  (.addListener chan listener))

(defmacro with-result
  "Marco to create future listeners"
  [[ftr action] & body]
  `(let [f# ^ChannelFuture ~action]
     (add-listener
      f#
      (reify ChannelFutureListener
        (operationComplete [this# ^ChannelFuture ~ftr]
          (do ~@body))))))

(defn operation-complete
  "Signal that operation completed on a listener"
  ([^ChannelFutureListener listener]
   (.operationComplete listener nil))
  ([^ChannelFutureListener listener future]
   (.operationComplete listener future)))

(defn listen-with
  [f]
  (reify
    io.netty.channel.ChannelFutureListener
    (operationComplete [this ftr]
      (f this ftr))))

(defmacro deflistener
  "Define a channel future listener"
  [sym [listener ftr bindings] & body]
  `(defn ~sym
     ~bindings
     (listen-with (fn [~listener ~ftr] (do ~@body)))))

(defn sync!
  "Synchronize future"
  [^ChannelFuture future]
  (.sync future))

(defn await!
  [^ChannelFuture future]
  (.await future))


(def close-listener
  "Listener which closes a channel"
  io.netty.channel.ChannelFutureListener/CLOSE)

(defn add-close-listener
  "Add close-listener to a channel"
  [^ChannelFuture chan]
  (.addListener chan io.netty.channel.ChannelFutureListener/CLOSE))
