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
    (is (= (reduce str input)
           (transduce (:xf transform) (:reducer transform) (:init transform) bufs)))))

(deftest edn-transform
  (let [input ["{"
               ":request"
               " "
               "0"
               "}"]
        bufs (mapv buf/wrapped-string input)]
    (let [{:keys [reducer xf init]} edn]
      (= (transduce xf reducer init bufs)
         {:request 0}))))
