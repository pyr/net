(ns net.transform.helpers
  (:require [net.ty.buffer :as buf])
  (:import java.io.File
           java.io.InputStream
           java.io.OutputStream
           java.nio.ByteBuffer
           java.nio.channels.FileChannel
           java.nio.file.attribute.FileAttribute
           java.nio.file.Files
           java.nio.file.FileSystems
           java.nio.file.OpenOption
           java.nio.file.Path
           java.nio.file.StandardOpenOption))
;; support functions
;; =================

(defn ^Path make-path
  [root]
  (.getPath (FileSystems/getDefault) root (into-array String [])))

(def read-options
  (into-array OpenOption [StandardOpenOption/READ]))

(def write-options
  (into-array OpenOption [StandardOpenOption/WRITE
                          StandardOpenOption/CREATE
                          StandardOpenOption/TRUNCATE_EXISTING]))

(def file-attrs (into-array FileAttribute []))

(defn create-temp-file
  [sz]
  (let [path ^Path (.toPath (doto (File/createTempFile "buftest" ".dat")
                              (.deleteOnExit)))
        chan ^FileChannel (FileChannel/open path write-options)]
    (dotimes [i (quot sz 1024)]
      (let [ba (byte-array (repeat 1024 (byte 0)))]
        (.write chan (ByteBuffer/wrap ba))))
    (.close chan)
    (str path)))

(defn delete-file
  [path]
  (-> path make-path .toFile .delete))

(defn ^InputStream input-stream
  [path]
  (Files/newInputStream (make-path path) read-options))

(defn ^OutputStream output-stream
  [path]
  (Files/newOutputStream (make-path path) write-options))

(defn lazy-bytes
  [^InputStream is bufsize]
  (lazy-seq
   (let [ba  (byte-array bufsize)
         len (.read is ba)]
     (if (neg? len)
       (.close is)
       (cons (doto (buf/direct-buffer len)
               (buf/write-bytes ba 0 len))
             (lazy-bytes is bufsize))))))

(defn buf-statistics
    [buf]
    (loop [s ""
           zeroes 0]
      (if (buf/exhausted? buf)
        {:visibile s :zeroes zeroes}
        (let [c (buf/read-char buf)]
          (if (zero? (byte c))
            (recur s (inc zeroes))
            (recur (str s c) zeroes))))))

(defn showbuf
  [b]
  (let [b (or (unwrap b) b)]
    (cond
      (and (composite? b) (pos? (refcount b)))
      (vec (conj (for [s (components b)] (showbuf s))
                 :>
                 (refcount b)))

      (composite? b)
      :dead-composite

      (pos? (refcount b))
      (refcount b)

      :else
      :dead)))

(def leaking-buffer-xf
  (comp (map #(when (pos? (buf/refcount %)) (showbuf %)))
        (map-indexed vector)
        (remove (comp nil? second))))

(def leaking-buffers
  (partial sequence leaking-buffer-xf))

;;
;;
(def add!
  (completing
   (fn [res buf]
     (let [rc (buf/refcount buf)
           sz (if (pos? rc) (buf/readable-bytes buf) 0)]
       (buf/skip-bytes buf)
       (buf/release buf)

       (-> res
           (update :count (fnil inc 0))
           (update :refcounts (fnil + 0) rc)
           (update :size (fnil + 0) sz))))))

(def kilo-bytes (partial * 1024))
(def mega-bytes (partial kilo-bytes 1024))
(def giga-bytes (partial mega-bytes 1024))

(def one-kb (kilo-bytes 1))
(def one-mb (mega-bytes 1))
(def one-gb (giga-bytes 1))

(defn bufcount
  [size bufsize]
  (cond-> (quot size bufsize) (pos? (rem size bufsize)) (+ 1)))
