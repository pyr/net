(ns net.http.request
  (:require [clojure.string   :as str]
            [net.http.chunk   :as chunk]
            [net.http.headers :as headers]
            [net.http.uri     :as uri]
            [net.codec.b64    :as b64])
  (:import io.netty.handler.codec.http.HttpRequest
           io.netty.handler.codec.http.DefaultHttpRequest
           io.netty.handler.codec.http.HttpMethod
           io.netty.handler.codec.http.HttpHeaders
           io.netty.handler.codec.http.HttpResponse
           io.netty.handler.codec.http.HttpResponseStatus
           io.netty.handler.codec.http.HttpVersion))

(def http-versions
  "Keyword HTTP versions"
  {:http-1-1 HttpVersion/HTTP_1_1
   :http-1-0 HttpVersion/HTTP_1_0})

(defn string->version
  "When possible, get appropriate HttpVersion from a string.
   Throws when impossible."
  [^String s]
  (cond
    (re-matches #"(?i)http/1.1" s) HttpVersion/HTTP_1_1
    (re-matches #"(?i)http/1.0" s) HttpVersion/HTTP_1_0))

(defn ^HttpVersion data->version
  "Get appropriate HttpVersion from a number of possible inputs.
   Defaults to HTTP/1.1 when nil, translate known keywords and
   strings or pass an HttpVersion through.

   Throws on other values."
  [v]
  (or
   (cond
     (nil? v)                  HttpVersion/HTTP_1_1
     (keyword? v)              (get http-versions v)
     (string? v)               (string->version v)
     (instance? HttpVersion v) v)
   (throw (IllegalArgumentException. (str "invalid http version: " v)))))

(def http-methods
  "Keyword HTTP methods"
  {:connect HttpMethod/CONNECT
   :delete  HttpMethod/DELETE
   :get     HttpMethod/GET
   :head    HttpMethod/HEAD
   :options HttpMethod/OPTIONS
   :patch   HttpMethod/PATCH
   :post    HttpMethod/POST
   :put     HttpMethod/PUT
   :trace   HttpMethod/TRACE})

(defn string->method
  [m]
  (-> m str/lower-case keyword))

(defn ^HttpMethod data->method
  "Get appropriate HttpMethod from a number of possible inputs.
   Defaults to GET when nil, translate known keywords and strings,
   or pass a HttpMethod through.

   Throws on other values."
  [m]
  (or
   (cond
     (instance? HttpMethod m) m
     (nil? m)                 HttpMethod/GET
     (keyword? m)             (get http-methods m)
     (string? m)              (string->method m))
   (IllegalArgumentException. (str "invalid http method: " m))))

(defn auth-map
  [{:keys [user password]}]
  (when (and (string? user) (string? password))
    {:authorization (str "Basic " (b64/s->b64 (str user ":" password)))}))

(defn data->request
  "Produce a valid request from a "
  [{:keys [uri host]} {:keys [headers request-method version query auth]}]
  (let [path    (uri/encoded-path uri query)
        version (data->version version)
        method  (data->method request-method)
        hmap    (merge {:host host} headers (auth-map auth))]
    (DefaultHttpRequest. version method path (headers/prepare hmap))))
