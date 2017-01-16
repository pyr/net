(ns net.core.async
  "Shamelessly stolen from @mpenet's jet,
   See https://github.com/mpenet/jet for original"
  (:require [clojure.core.async :as async]))

(defn put!
  "Takes a `ch`, a `msg`, a single arg function that when passed
   `true` enables backpressure and when passed `false` disables it,
   and a no-arg function which, when invoked, closes the upstream
   source."
  ([ch msg backpressure! close!]
   (let [status (atom ::sending)]
     (async/put! ch msg
                 (fn [result]
                   (if-not result
                     (when close! (close!))
                     (cond
                       (compare-and-set! status ::sending ::sent)
                       nil
                       (compare-and-set! status ::paused  ::sent)
                       (backpressure! false)))))
     ;; it's still sending, means it's parked, so suspend source
     (when (compare-and-set! status ::sending ::paused)
       (backpressure! true))
     nil))
  ([ch msg backpressure!]
   (put! ch msg backpressure! nil)))

(defn validating-fn
  [spec always-assert?]
  (if always-assert?
    (fn [x] (s/assert* spec x))
    (fn [x] (s/assert spec x))))

(defn validating-promise-chan
  "A promise chan which ensures that values produced
   to it match a given spec. Failing to match the spec
   will produce `error-value` on the channel.

   When `error-value` is a function, call it with no
   args to produce the error value, or produce
   `error-value` itself.

   When `always-assert?` is provided, force asserts,
   regardless of the value of `*clojure.core/compile-asserts*`,
   otherwise, and by default honor the value.

   The 1-arity version produces nil on the chan in case of errors."
  ([spec error-value always-assert?]
   (a/promise-chan (map (validating-fn spec always-assert?))
                   (fn [_] (if (fn? error-value)
                             (error-value)
                             error-value))))
  ([spec error-value]
   (validating-promise-chan spec error-value false))
  ([spec]
   (validating-promise-chan spec nil false)))

(defn validating-chan
  "A chan which ensures that values produced
   to it match a given spec. Failing to match the spec
   will produce `error-value` on the channel.

   When `error-value` is a function, call it with no
   args to produce the error value, or produce
   `error-value` itself.

   When `always-assert?` is provided, force asserts,
   regardless of the value of `*clojure.core/compile-asserts*`,
   otherwise, and by default honor the value.

   The 1-arity version produces nil on the chan in case of errors."
  ([spec buf-or-n error-value always-assert?]
   (a/chan buf-or-n (map (validating-fn spec always-assert?))
           (fn [_] (if (fn? error-value)
                     (error-value)
                     error-value))))
  ([spec buf-or-n error-value]
   (validating-chan spec buf-or-n error-value false))
  ([spec buf-or-n]
   (validating-chan spec buf-or-n nil false)))
