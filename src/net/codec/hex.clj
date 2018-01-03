(ns net.codec.hex
  "Hexadecimal encoding facilities")

(defn hex->long [^String s]
  (try
    (Long/parseUnsignedLong s (int 16))
    (catch Exception e
      (let [ba (.getBytes s)]
        (throw (ex-info "cannot parse input string as hex value"
                        {:sample  (vec (take 5 (seq ba)))
                         :zeroes (reduce + 0 (remove zero?  ba))
                         :str (reduce str (map char (remove zero? ba)))
                         :size    (count (.getBytes s))}))))))
