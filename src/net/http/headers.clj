(ns net.http.headers
  (:require [clojure.string :as str])
  (:import io.netty.handler.codec.http.DefaultHttpHeaders
           io.netty.handler.codec.http.HttpHeaders))

(defn put!
  [^HttpHeaders headers k v]
  (.set headers (name k) (str v)))

(defn ^HttpHeaders prepare
  ([^HttpHeaders headers input]
   (when-not (or (empty? input) (map? input))
     (throw (IllegalArgumentException.
             "HTTP headers should be supplied as a map")))
   (let [hmap (reduce-kv #(assoc %1 (keyword %2) %3) {} input)]
     (when-not (:connection hmap)
       (put! headers :connection "close"))
     (doseq [[k v] input]
       (put! headers k v))
     headers))
  ([input]
   (prepare (DefaultHttpHeaders. true) input)))

(defn as-map
  "Get a map out of netty headers."
  [^HttpHeaders headers]
  (into
   {}
   (map (fn [[k v]] [(-> k str/lower-case keyword) v])) (.entries headers)))
