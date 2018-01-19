(ns net.http.chunk
  (:require [net.ty.buffer      :as buf]
            [net.ty.channel     :as chan]
            [net.http           :as http]
            [clojure.core.async :as a]
            [net.core.async     :refer [put!]])
  (:import io.netty.buffer.Unpooled
           io.netty.buffer.ByteBuf
           io.netty.handler.codec.http.DefaultHttpContent
           io.netty.handler.codec.http.HttpContent
           java.io.InputStream
           java.io.File
           java.io.FileInputStream
           java.nio.charset.Charset
           java.nio.ByteBuffer
           clojure.core.async.impl.protocols.Channel))

(defn body-chan
  [inbuf {:keys [reducer xf init]}]
  (cond
    (some? reducer)
    (let [ch (a/chan inbuf)]
      [ch (a/transduce xf reducer (or init (reducer)) ch)])

    (some? xf)
    (let [ch (a/chan inbuf xf)] [ch ch])

    :else
    (let [ch (a/chan inbuf)] [ch ch])))

(defn input-stream-chunk
  "Fill up a ByteBuf with the contents of an input stream"
  [^InputStream is]
  (let [buf (Unpooled/buffer (.available is))]
    (loop [len (.available is)]
      (when (pos? len)
        (.writeBytes buf is len)
        (recur (.available is))))
    buf))

(defn file-chunk
  "Create an input stream chunk from a File"
  [^File f]
  (input-stream-chunk (FileInputStream. f)))

(defprotocol ChunkEncoder
  "A simple encoding protocol for chunks"
  (any->http-object [chunk] "Convert arbitrary data to an HttpContent instance"))

(defn chunk->http-object
  [chunk]
  (cond
    (bytes? chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer ^"[B" chunk))

    (string? chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer
                          (.getBytes ^String chunk "UTF8")))

    (instance? ByteBuf chunk)
    (DefaultHttpContent. chunk)

    (instance? ByteBuffer chunk)
    (DefaultHttpContent. (Unpooled/wrappedBuffer ^ByteBuffer chunk))

    (instance? InputStream chunk)
    (DefaultHttpContent. (input-stream-chunk chunk))

    (instance? File chunk)
    (DefaultHttpContent. (file-chunk chunk))

    (satisfies? ChunkEncoder chunk)
    (any->http-object chunk)

    :else
    (throw (IllegalArgumentException. "Cannot coerce to HttpContent"))))

(defn content-chunk?
  "Predicate to check for ChunkEncoder compliance"
  [x]
  (or (bytes? x)
      (string? x)
      (instance? ByteBuf x)
      (instance? HttpContent x)
      (instance? ByteBuffer x)
      (instance? InputStream x)
      (instance? File x)
      (satisfies? ChunkEncoder x)))

(defn backpressure-fn
  "Stop automatically reading from the body channel when we are signalled
   for backpressure."
  [ctx]
  (let [cfg (-> ctx chan/channel chan/config)]
    (fn [enable?]
      (chan/set-autoread! cfg (not enable?)))))

(defn close-fn
  "A closure over a context that will close it when called."
  [msg ctx]
  (fn []
    (buf/release msg)
    (-> ctx chan/channel chan/close-future)))

(defn enqueue
  [sink ctx msg]
  (put! sink (buf/as-buffer msg) (backpressure-fn ctx) (close-fn msg ctx))
  (when (http/last-http-content? msg)
    (-> ctx chan/channel chan/close-future)
    (a/close! sink)))

(defn prepare-body
  [x]
  (cond
    (nil? x)              http/last-http-content
    (content-chunk? x)    (chunk->http-object x)
    (instance? Channel x) x
    :else                 (throw (IllegalArgumentException.
                                  "Cannot coerce body to HttpContent"))))
