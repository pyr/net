(ns net.transform.aggregator-test
  (:require [clojure.test :refer :all]
            [net.transform.aggregator :refer :all]
            [net.transform.helpers :refer :all]
            [net.ty.buffer :as buf]))

(deftest aggregating-buffers
  (doseq [size [(mega-bytes 10) (mega-bytes 100)]
          :let [path (create-temp-file size)]]
    (doseq [agg-size  [(kilo-bytes 79) (mega-bytes 3) (mega-bytes 16)]
            read-size [(kilo-bytes 33) (kilo-bytes 64)]
            :let      [input   (lazy-bytes (input-stream path) read-size)
                       output  (transduce (aggregator agg-size) add! {} input)
                       inbufs  (bufcount size read-size)
                       outbufs (bufcount size agg-size)]]
      (testing (format "aggregating %d %d-byte chunks from %d bytes in %d bufs"
                       outbufs agg-size size inbufs)
        (is (= inbufs (count input)))
        (is (= outbufs (:count output)))
        (is (= size (:size output)))
        (is (empty? (leaking-buffers input)))))
    (delete-file path)))


(deftest small-aggregation
  (testing "small bufs"
    (let [bufs (map buf/wrapped-bytes [(byte-array (repeat 200 (byte 0)))])
          output (sequence (aggregator 1024) bufs)]
      (is (= (count output) (count bufs))))))
