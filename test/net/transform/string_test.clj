(ns net.transform.string-test
  (:require [clojure.test :refer :all]
            [net.transform.string :refer :all]
            [net.transform.helpers :refer :all]
            [net.ty.buffer :as buf]))

(deftest aggregating-buffers
  (let [input ["foobar\n"
               "foobar\n"
               "foobar\n"
               "foobar\n"
               "foobar\n"
               "foobar\n"
               "foobar\n"
               "foobar\n"
               "foobar\n"]
        bufs  (mapv buf/wrapped-string input)]
    (is (= {:values (reduce str input)}
           (transduce (:xf transform) (:reducer transform) (:init transform) bufs)))))
