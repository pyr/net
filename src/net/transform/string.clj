(ns net.transform.string
  (:require [net.ty.buffer :as buf]))

(def transform
  "A naive transform definition"
  {:reducer (completing str)
   :xf      (map buf/to-string)
   :init    ""})
