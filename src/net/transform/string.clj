(ns net.transform.string
  (:require [net.ty.buffer :as buf]))

(def transform
  {:reducer (completing str)
   :xf      (map buf/to-string)
   :init    ""})
