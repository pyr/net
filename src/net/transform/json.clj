(ns net.transform.json
  (:require [net.ty.buffer :as buf]
            [cheshire.core :as json]))

(defn json-reducer
  ([]      "")
  ([final] (json/parse-string final true))
  ([s in]  (str s (buf/to-string in))))

(def decoder
  {:reducer json-reducer
   :xf      (map identity)
   :init    ""})
