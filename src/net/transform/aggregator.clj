(ns net.transform.aggregator
  "A buffer aggregator, when a number of smaller ByteBuf instances need to
   be aggregated over a larger CompositeByteBuf instance.

   These aggregated views rely on the underlying buf and handle memory
   accounting for it, releasing the aggregated view will release the
   underlying buffers they are composed of."
  (:require [net.ty.buffer :as buf]))

(defn aggregate
  "Offer a new buffer for aggregation, yielding a vector
   of bytes kept and buffers to yield if any. We may yield
   several buffers if the aggregation size is smaller than
   the buffer we were offered."
  [store buf max-size]
  (loop [kept (first store)
         bufs []]
    (let [kept-len   (if kept (buf/readable-bytes kept) 0)
          buf-len    (buf/readable-bytes buf)
          total-len  (+ kept-len buf-len)
          slice-len  (- (min max-size total-len) kept-len)
          kept-buf   #(or kept (buf/composite))]
      (cond
        (buf/exhausted? buf)
        [kept bufs]

        (= total-len max-size)
        [nil (conj bufs (cond->> (buf/read-retained-slice buf)
                          (some? kept) (buf/augment-composite kept)))]

        (> total-len max-size)
        (let [slice (buf/read-retained-slice buf slice-len)]
          (recur
           nil
           (conj bufs (cond->> slice (some? kept) (buf/augment-composite kept)))))

        :else
        [(buf/augment-composite (kept-buf) (buf/read-retained-slice buf)) bufs]))))

(defn raw-aggregator
  "A transducer that yields collections of ByteBuf instances from
   another collection of ByteBuf instances, in the output collection
   all but the last element of the collection will contain ByteBuf
   instances of `max-size` bytes. The last one may be smaller."
  [max-size]
  (fn [xf]
    (let [store (volatile! nil)]
      (fn
        ([] (xf))
        ([result]
         (if-let [tail (first @store)]
           (xf (unreduced (xf result [tail])))
           (xf result)))
        ([result input]
         (let [[_ bufs] (vswap! store aggregate input max-size)]
           (buf/release input)
           (if (seq bufs)
             (xf result bufs)
             result)))))))

(defn aggregator
  "Facility function to create an aggregator which yields a single
   collection of ByteBuf instances."
  [max-size]
  (comp (raw-aggregator max-size) cat))

(def default-chunk-size
  "Documentation suggests 16 mega bytes is the ideal size."
  (* 1024 1024 16))

(def default-aggregator
  "Aggregate to the optimal blob size by default."
  (aggregator default-chunk-size))
