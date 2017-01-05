(ns net.ty.buffer
  (:require [clojure.tools.logging :refer [info debug]])
  (:import io.netty.buffer.Unpooled
           io.netty.handler.codec.http.DefaultHttpResponse
           io.netty.handler.codec.http.DefaultHttpContent
           io.netty.handler.codec.http.DefaultLastHttpContent
           io.netty.handler.codec.http.HttpRequest
           io.netty.handler.codec.http.HttpContent
           io.netty.handler.codec.http.LastHttpContent))

(defn buffer-holder
  []
  (volatile! nil))

(defn augment-buffer
  ([dst src]
   (.writeBytes dst src))
  ([dst src len]
   (.writeBytes dst src (int len))))

(defn new-buffer
  ([max-size]
   (Unpooled/buffer (int (* 1024 1024)) (int max-size)))
  ([init-size max-size]
   (Unpooled/buffer (int init-size) (int max-size))))

(defn update-content
  [max-size content msg]
  (locking content
    (let [buf    @content
          msg-bb (.content msg)
          msg-sz (.capacity msg-bb)
          buf-sz (if buf (.writerIndex buf) 0)
          new-sz (+ buf-sz msg-sz)
          pad-sz (- max-size buf-sz)]
      (debug "capacity held    :" (if buf (.capacity buf) 0))
      (debug "index held       :" (if buf (.writerIndex buf) 0))
      (debug "capacity provided:" (if msg-bb (.capacity msg-bb) 0))
      (debug "buffer size now  :" buf-sz)
      (debug "new size         :" new-sz)
      (debug "pad size         :" (- max-size buf-sz))
      (cond
        (and (nil? buf) (= max-size msg-sz))
        (do
          (debug "nil buf, msg size matches max-chunk, forwarding")
          (DefaultHttpContent. (Unpooled/copiedBuffer msg-bb)))

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
  [max-size content msg]
  (locking content
    (let [buf    @content
          msg-bb (.content msg)
          msg-sz (.capacity msg-bb)
          buf-sz (if buf (.writerIndex buf) 0)
          new-sz (+ buf-sz msg-sz)
          pad-sz (- max-size buf-sz)]
      (debug "capacity held    :" (if buf (.capacity buf) 0))
      (debug "index held       :" (if buf (.writerIndex buf) 0))
      (debug "capacity provided:" (if msg-bb (.capacity msg-bb) 0))
      (debug "buffer size now  :" buf-sz)
      (debug "new size         :" new-sz)
      (debug "pad size         :" (- max-size buf-sz))
      (cond
        (nil? buf)
        (do
          (debug "lonely http content")
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
  [msg]
  (let [has-interface? (-> msg .getClass supers set)]
    (has-interface? LastHttpContent)))
