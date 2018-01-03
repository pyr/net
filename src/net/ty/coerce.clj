(ns net.ty.coerce
  "Our beloved JVM does not implement unsigned types.
   Some protocols rely heavily on unsigned types,
   and we thus need to have ways of parsing those")

(def uint16-max "short maximum value"   0x10000)
(def uint32-max "integer maximum value" 0x100000000)

(defn ubyte->byte
  [i]
  (-> i short Short. .byteValue))

(defn ushort->short
  [i]
  (-> i int Integer. .shortValue short))

(defn uint->int
  [i]
  (-> i long Long. .intValue int))

(defn ulong->long
  [i]
  (-> i bigint .longValue long))

(defn byte->ubyte
  [i]
  (-> i short Short. (bit-and 0xff)))

(defn short->ushort
  [i]
  (-> i int Integer. (bit-and 0xffff)))

(defn int->uint
  [i]
  (-> i long Long. (bit-and 0xffffffff)))

(defn long->ulong
  [i]
  (let [buf (doto (java.nio.ByteBuffer/allocate 8) (.putLong i))]
    (long (BigInteger. 1 (.array buf)))))
