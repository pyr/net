(ns net.http.uri
  (:require [clojure.string :as str])
  (:import java.net.URI
           io.netty.handler.codec.http.QueryStringEncoder))

(defn ^URI encode
  "Produce a valid URI from arguments found in a request map"
  [^URI uri query]
  (if (seq query)
    (let [qse (QueryStringEncoder. (str uri))]
      (doseq [[k v] query]
        (if (sequential? v)
          (doseq [subv v]
            (.addParam qse (name k) (str subv)))
          (.addParam qse (name k) (str v))))
      (.toUri qse))
    uri))

(defn raw-path
  [^URI uri]
  (.getRawPath uri))

(defn raw-query
  [^URI uri]
  (.getRawQuery uri))

(defn host
  [^URI uri]
  (.getHost uri))

(defn ^String encoded-path
  [^URI uri query]
  (let [encoded (encode uri query)
        raw-q   (raw-query uri)]
    (cond-> (raw-path encoded)
      (seq raw-q) (str "?" raw-q))))

(defn ^String scheme
  ([^URI uri]
   (when-let [s (.getScheme uri)]
     (str/lower-case s)))
  ([^URI uri ^String default]
   (or (scheme uri) default)))

(defn ^int port
  [^URI uri]
  (let [p (.getPort uri)]
    (when-not (= p -1)
      p)))

(defn ^URI ->uri
  [input]
  (cond
    (string? input)       (URI. input)
    (instance? URI input) input
    :else                 (throw (IllegalArgumentException.
                                  "Invalid URI provided"))))

(defn parse
  ([input params]
   (let [uri        (encode (->uri input) params)
         uri-port   (port uri)
         uri-scheme (scheme uri)]
     {:uri    uri
      :host   (host uri)
      :scheme uri-scheme
      :port   (cond (some? uri-port)       uri-port
                    (= "http" uri-scheme)  80
                    (= "https" uri-scheme) 443)
      :ssl?   (= "https" uri-scheme)}))
  ([input]
   (parse input {})))
