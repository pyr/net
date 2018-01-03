(ns net.transform.split
  "A generic splitting transducer for collections of ByteBuf instances
   To help the splitting process, and to allow other types of splitting
   to occur, two protocols are provided."
  (:require [net.ty.buffer :as buf]
            [net.codec.hex :refer [hex->long]]))

(defprotocol ContentSplitter
  (offer [this buf]
    "Augment a splitter instance with a new buffer,
     yielding a vector of the updated splitter and
     a new buffer if one was found."))

(defprotocol HeaderParser
  (header-length [this buf]
    "Yield length of header needed to compute the length
     of the split, if possible.")
  (payload-length [this mark kept buf]
    "Yield the length of the full split payload, this
     assumes header-length was called and returned."))

(defprotocol Initializer
  (initialize! [this]
    "Initialize a splitter, called everytime a new buffer is output
     to reset internal state."))

(defn bytes-needed
  "Figure out how many bytes need to be read to finish this split."
  [parser kept buf]
  (let [kept-len (if (some? kept) (buf/readable-bytes kept) 0)]
    (when-let [mark (header-length parser buf)]
      (+ kept-len (payload-length parser mark kept buf)))))

;;
;; A header splitter, which depends on the help of a HeaderParser
;; implementation to figure out expected size
(defrecord HeaderSplitter [parser need kept]
  Initializer
  (initialize! [this]
    (assoc this :need nil :kept nil))
  ContentSplitter
  (offer [this buf]
    (let [kept-len  (if kept (buf/readable-bytes kept) 0)
          buf-len   (buf/readable-bytes buf)
          total-len (+ kept-len buf-len)
          need      (or need (bytes-needed parser kept buf))]
      (cond
        (nil? need)
        [(assoc this :kept (buf/augment-composite (or kept (buf/composite))
                                                  (buf/read-retained-slice buf)))]

        (and (number? need) (>= total-len need))
        [(initialize! this)
         (cond->> (buf/read-retained-slice buf (- need kept-len))
           (some? kept) (buf/augment-composite kept))]

        :else
        [(assoc this
                :kept (buf/augment-composite (or kept (buf/composite))
                                             (buf/read-retained-slice buf))
                :need need)]))))

(defn parse-split
  "With a new buffer, augment a splitter by consuming all data from the
   buffer. Yields a vector of the augmented splitter and a sequence of
   new buffers to output, if any."
  [stored buf]
  (loop [splitter (first stored)
         outbufs  []]
    (let [[splitter outbuf] (offer splitter buf)
          outbufs           (cond-> outbufs (some? outbuf) (conj outbuf))]
      (if (buf/exhausted? buf)
        [splitter outbufs]
        (recur splitter outbufs)))))

(defn raw-split
  "A transducer that yields collections of ByteBuf instances, from
   a collection of ByteBuf instances, splitting with the help of
   a ContentSplitter implementation."
  [splitter]
  (fn [xf]
    (let [sp (volatile! [(initialize! splitter)])]
      (fn
        ([] (xf))
        ([result] (xf result))
        ([result input]
         (let [[splitter outbufs] (vswap! sp parse-split input)]
           (buf/release input)
           (if (seq outbufs)
             (xf result outbufs)
             result)))))))

(defn split
  "Facility function to create a splitter which yields a single
   collection of ByteBuf instances."
  [splitter]
  (comp (raw-split splitter) cat))
