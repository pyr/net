(ns net.ty.buffer-test
  (:require [clojure.test :refer :all]
            [net.ty.buffer :refer :all]
            [clojure.core.async :as a]))

(def one? (partial = 1))
(def two? (partial = 2))

(deftest composite-buffer
  (let [parts ["f" "o" "o" "b" "a" "r"]
        cb    (composite)]
    (doseq [p parts]
      (augment-composite cb (wrapped-string p)))
    (is (composite? cb))
    (is (= "foobar" (to-string cb)))))


(deftest wrapped-bufer
  (let [parts ["foo" "bar"]
        cb    (merge-bufs (mapv wrapped-string parts))]
    (is (composite? cb))
    (is (= "foobar" (to-string cb)))))

(deftest wrapped-string-retention
  (let [b (wrapped-string "foo")]
    (is (one? (refcount b)))
    (retain b)
    (is (two? (refcount b)))
    (release b)
    (is (one? (refcount b)))
    (release b)
    (is (zero? (refcount b)))
    (is (thrown-with-msg? Exception #"refCnt" (read-byte b)))))

(deftest retained-slice-retention
  (let [b (wrapped-string "foo")
        s (retained-slice b)]
    (is (two? (refcount b)))
    (is (two? (refcount s)))
    (read-byte s)
    (is (zero? (reader-index b)))
    (is (one? (reader-index s)))
    (release s)
    (is (one? (refcount b)))
    (is (one? (refcount s)))
    (release b)
    (is (zero? (refcount b)))
    (is (zero? (refcount s)))))

(deftest slice-retention
  (let [b (wrapped-string "foo")
        s (slice b)]
    (is (one? (refcount b)))
    (is (one? (refcount s)))
    (retain s)
    (is (two? (refcount b)))
    (is (two? (refcount s)))
    (read-byte s)
    (is (zero? (reader-index b)))
    (is (one? (reader-index s)))
    (release s)
    (is (one? (refcount b)))
    (is (one? (refcount s)))
    (release b)
    (is (zero? (refcount b)))
    (is (zero? (refcount s)))))

(deftest composite-retention
  (let [parts (mapv wrapped-string ["f" "o" "o" "b" "a" "r"])
        cb    (composite)]
    (doseq [p parts]
      (augment-composite cb p))
    (is (one? (refcount cb)))
    (is (every? one? (map refcount parts)))
    (doseq [p parts]
      (retain p))
    (is (one? (refcount cb)))
    (is (every? two? (map refcount parts)))
    (doseq [p parts]
      (release p))
    (retain cb)
    (is (every? one? (map refcount parts)))
    (release cb)
    (is (one? (refcount cb)))
    (retain-all cb)
    (is (every? two? (map refcount parts)))
    (is (two? (refcount cb)))
    (release-all cb)
    (is (every? one? (map refcount parts)))
    (is (one? (refcount cb)))))


;; Same tests, but on direct buffers
;;

(defn direct-wrapped-string
  [s]
  (write-string (direct-buffer) s))

(deftest direct-composite-buffer
  (let [parts ["f" "o" "o" "b" "a" "r"]
        cb    (composite)]
    (doseq [p parts]
      (augment-composite cb (direct-wrapped-string p)))
    (is (composite? cb))
    (is (= "foobar" (to-string cb)))))


(deftest direct-wrapped-bufer
  (let [parts ["foo" "bar"]
        cb    (merge-bufs (mapv direct-wrapped-string parts))]
    (is (composite? cb))
    (is (= "foobar" (to-string cb)))))

(deftest direct-wrapped-string-retention
  (let [b (direct-wrapped-string "foo")]
    (is (one? (refcount b)))
    (retain b)
    (is (two? (refcount b)))
    (release b)
    (is (one? (refcount b)))
    (release b)
    (is (zero? (refcount b)))
    (is (thrown-with-msg? Exception #"refCnt" (read-byte b)))))

(deftest direct-retained-slice-retention
  (let [b (direct-wrapped-string "foo")
        s (retained-slice b)]
    (is (two? (refcount b)))
    (is (two? (refcount s)))
    (read-byte s)
    (is (zero? (reader-index b)))
    (is (one? (reader-index s)))
    (release s)
    (is (one? (refcount b)))
    (is (one? (refcount s)))
    (release b)
    (is (zero? (refcount b)))
    (is (zero? (refcount s)))))

(deftest direct-slice-retention
  (let [b (direct-wrapped-string "foo")
        s (slice b)]
    (is (one? (refcount b)))
    (is (one? (refcount s)))
    (retain s)
    (is (two? (refcount b)))
    (is (two? (refcount s)))
    (read-byte s)
    (is (zero? (reader-index b)))
    (is (one? (reader-index s)))
    (release s)
    (is (one? (refcount b)))
    (is (one? (refcount s)))
    (release b)
    (is (zero? (refcount b)))
    (is (zero? (refcount s)))))

(deftest direct-composite-retention
  (let [parts (mapv direct-wrapped-string ["f" "o" "o" "b" "a" "r"])
        cb    (composite)]
    (doseq [p parts]
      (augment-composite cb p))
    (is (one? (refcount cb)))
    (is (every? one? (map refcount parts)))
    (doseq [p parts]
      (retain p))
    (is (one? (refcount cb)))
    (is (every? two? (map refcount parts)))
    (doseq [p parts]
      (release p))
    (retain cb)
    (is (every? one? (map refcount parts)))
    (release cb)
    (is (one? (refcount cb)))
    (retain-all cb)
    (is (every? two? (map refcount parts)))
    (is (two? (refcount cb)))
    (release-all cb)
    (is (every? one? (map refcount parts)))
    (is (one? (refcount cb)))))

;; Composite buffer behavior

(deftest composite-buffer-slices
  (let [parts (mapv direct-wrapped-string ["f" "o" "o" "b" "a" "r"])
        cb (composite)]
    (doseq [p parts]
      (augment-composite cb p))
    (is (= (set parts) (set (iterator-seq (component-iterator cb)))))
    (is (composite? cb))
    (is (not (composite? (slice cb))))
    (is (= cb (unwrap (slice (slice (slice cb))))))))

(deftest composite-reassembly
  (let [p1 (mapv direct-wrapped-string ["f" "o" "o"])
        p2 (mapv direct-wrapped-string ["b" "a" "r"])
        ps (vec (concat p1 p2))
        c1 (composite)
        c2 (composite)
        c3 (composite)]
    (doseq [p p1]
      (augment-composite c1 p))
    (doseq [p p2]
      (augment-composite c2 p))
    (doseq [p [c1 c2]]
      (augment-composite c3 p))
    (is (= [c1 c2] (vec (components c3))))))

;;
;; Asynchronous buffers
;; ====================

(deftest async-buffer-lifetime

  (let [ch (a/chan 10)]
    (let [parts (mapv direct-wrapped-string ["f" "o" "o" "b" "a" "r"])
          cb    (composite)]
      (doseq [p parts]
        (augment-composite cb p)
        (a/put! ch p))
      (a/put! ch cb)
      (a/close! ch)

      ;; Ensure
      (Thread/sleep 200)
      (System/gc)
      (Thread/sleep 200)
      (System/gc)
        ;;
      (let [vals (loop [res []] (if-let [v (a/<!! ch)] (recur (conj res v)) res))]
        (is (every? (partial = 1) (map refcount vals)))
        ;; We enqueued the composite last, we can now release it
        (release (last vals))
        (is (every? zero? (map refcount vals)))))))
