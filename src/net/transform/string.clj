(ns net.transform.string
  (:require [net.ty.buffer :as buf]
            [clojure.edn   :as edn]))

(def transform
  "A naive transform definition"
  {:reducer (completing str)
   :xf      (map #(buf/to-string % true))
   :init    ""})

(defn transform-with
  [finalizer]
  {:reducer (fn
              ([]    "")
              ([v]   (finalizer v))
              ([a b] (str a b)))
   :xf      (map #(buf/to-string % true))
   :init    ""})

(def edn
  "An example finalizing transform which deserializes EDN"
  (transform-with edn/read-string))
