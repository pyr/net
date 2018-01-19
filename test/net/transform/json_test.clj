(ns net.transform.json-test
  (:require [clojure.test :refer :all]
            [net.transform.json :refer :all]
            [net.transform.helpers :refer :all]
            [net.ty.buffer :as buf]))

(deftest aggregating-buffers
  (let [input ["{\"values\":["
               "{\"foo\":\"bar\"},"
               "{\"foo\":\"bar\"},"
               "{\"foo\":\"bar\"},"
               "{\"foo\":\"bar\"},"
               "{\"foo\":\"bar\"},"
               "{\"foo\":\"bar\"},"
               "{\"foo\":\"bar\"}"
               "]}"]
        bufs  (mapv buf/wrapped-string input)]
    (is (= {:values [{:foo "bar"}
                     {:foo "bar"}
                     {:foo "bar"}
                     {:foo "bar"}
                     {:foo "bar"}
                     {:foo "bar"}
                     {:foo "bar"}]}
           (transduce (:xf decoder) (:reducer decoder) (:init decoder) bufs)))))
