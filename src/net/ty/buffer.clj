(ns net.ty.buffer
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
          buf-sz (if buf (.capacity buf) 0)]
      (cond
        (nil? buf)
        (do
          (vreset! content (Unpooled/copiedBuffer msg-bb))
          nil)

        (<= 0 (+ msg-sz buf-sz) max-size)
        (do
          (augment-buffer @content msg-bb)
          nil)

        :else
        (do
          (let [bb (new-buffer max-size)]
            (augment-buffer buf msg-bb)
            (augment-buffer bb msg-bb (- max-size buf-sz))
            (vreset! content bb)
            (DefaultHttpContent. buf)))))))

(defn release-contents
  [max-size content msg]
  (locking content
    (let [buf @content
          msg-bb (.content msg)
          msg-sz (.capacity msg-bb)
          buf-sz (if buf (.capacity buf) 0)]
      (cond
        (nil? buf)
        (do
          (.retain (.content msg))
          [(DefaultLastHttpContent. (.content msg))])

        (<= 0 (+ msg-sz buf-sz) max-size)
        [(DefaultLastHttpContent. (augment-buffer buf msg-bb))]

        :else
        [(DefaultHttpContent. (augment-buffer buf msg-bb (- max-size buf-sz)))
         msg]))))


(defn last-http-content?
  [msg]
  (let [has-interface? (-> msg .getClass supers set)]
    (has-interface? LastHttpContent)))
