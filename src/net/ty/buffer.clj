(ns net.ty.buffer
  "Utility functions to build fixed size buffers.
   These functions hold onto data and yield when
   a specific size has been reached."
  (:require [clojure.tools.logging :refer [debug]])
  (:import io.netty.buffer.Unpooled
           io.netty.buffer.ByteBuf
           io.netty.handler.codec.http.DefaultHttpResponse
           io.netty.handler.codec.http.DefaultHttpContent
           io.netty.handler.codec.http.DefaultLastHttpContent
           io.netty.handler.codec.http.HttpRequest
           io.netty.handler.codec.http.HttpContent
           io.netty.handler.codec.http.LastHttpContent
           java.nio.ByteBuffer))

(defn buffer-holder
  "Create a buffer holder (internally a volatile)."
  []
  (volatile! nil))

(defn augment-buffer
  "Add bytes to a buffer"
  ([^ByteBuf dst ^ByteBuf src]
   (if (pos? (.capacity src))
     (.writeBytes dst src)
     dst))
  ([^ByteBuf dst ^ByteBuf src len]
   (.writeBytes dst src (int len))))

(defn new-buffer
  "Create a new ByteBuf"
  ([max-size]
   (Unpooled/buffer (int (* 1024 1024)) (int max-size)))
  ([init-size max-size]
   (Unpooled/buffer (int init-size) (int max-size))))

(defn update-content
  "Add bytes to a buffer, outputs HttpContent instances containing
   the expected size, or nothing."
  [max-size ^HttpContent content ^HttpContent msg]
  (locking content
    (let [buf    ^ByteBuf @content
          msg-bb ^ByteBuf (.content  msg)
          msg-sz (.capacity msg-bb)
          buf-sz (if buf (.writerIndex buf) 0)
          new-sz (+ buf-sz msg-sz)
          pad-sz (- max-size buf-sz)]
      (cond
        (and (nil? buf) (= max-size msg-sz))
        (do
          (debug "nil buf, msg size matches max-chunk, forwarding")
          (DefaultHttpContent. ^ByteBuf (Unpooled/copiedBuffer msg-bb)))

        (nil? buf)
        (do
          (debug "nil buf, storing copy of input")
          (vreset! content (Unpooled/copiedBuffer msg-bb))
          nil)

        (< new-sz max-size)
        (do
          (debug "buffer fits")
          (augment-buffer buf msg-bb)
          nil)

        (= new-sz max-size)
        (do
          (debug "buffer fills perfectly")
          (augment-buffer buf msg-bb)
          (vreset! content nil)
          (DefaultHttpContent. buf))

        :else
        (do
          (let [bb (new-buffer max-size max-size)]
            (debug "adding" pad-sz "bytes to msg-bb which already contains"  msg-sz)
            (augment-buffer buf msg-bb pad-sz)
            (augment-buffer bb msg-bb (- msg-sz pad-sz))
            (vreset! content bb)
            (DefaultHttpContent. buf)))))))

(defn release-contents
  "Add bytes to a buffer, and then flush all output. Yields a vector
  of HttpContent instances."
  [max-size
   ^HttpContent content
   ^HttpContent msg]
  (locking content
    (let [buf    @content
          msg-bb (.content msg)
          msg-sz (.capacity msg-bb)
          buf-sz (if buf (.writerIndex ^ByteBuf buf) 0)
          new-sz (+ buf-sz msg-sz)
          pad-sz (- max-size buf-sz)]
      (cond
        (nil? buf)
        (do
          [(DefaultLastHttpContent. (Unpooled/copiedBuffer msg-bb))])

        (<= new-sz max-size)
        (do
          (debug "last-http content fits into existing buffer")
          (augment-buffer buf msg-bb)
          [(DefaultLastHttpContent. buf)])

        :else
        (do
          (debug "last-http content does not fit, splitting")
          (augment-buffer buf msg-bb pad-sz)
          [(DefaultHttpContent. buf)
           (DefaultLastHttpContent. (Unpooled/copiedBuffer msg-bb))])))))


(defn last-http-content?
  "Are we dealing with an instance of LastHttpContent"
  [msg]
  (instance? LastHttpContent msg))
