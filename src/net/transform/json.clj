(ns net.transform.json
  (:require [net.ty.buffer :as buf]
            [cheshire.core :as json]))

(defn json-reducer
  ([]      "")
  ([final] (json/parse-string final true))
  ([s in]  (str s (buf/to-string in))))


(def transform
  {:reducer json-reducer
   :xf      (map identity)
   :init    ""})

(comment
  (http/client {:uri       "http://localhost:8000"
                :transform json/transform})
  )
