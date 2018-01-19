(ns net.transform.string
  (:require [net.ty.buffer :as buf]))

(defn released-string
  [buf]
  (let [s (buf/to-string buf)]
    (buf/release buf)
    s))

(def transform
  "A naive transform definition"
  {:reducer (completing str)
   :xf      (map released-string)
   :init    ""})
