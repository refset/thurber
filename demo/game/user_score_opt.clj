(ns game.user-score-opt
  (:require [thurber :as th]
            [clojure.tools.logging :as log]
            [deercreeklabs.lancaster :as lan])
  (:import (org.apache.beam.sdk.io TextIO)
           (org.apache.beam.sdk.values KV)
           (org.apache.beam.sdk.transforms Sum)
           (thurber.java TCoder)
           (org.apache.beam.sdk.coders CustomCoder KvCoder StringUtf8Coder VarIntCoder)
           (java.io OutputStream InputStream)
           (java.nio ByteBuffer)))

(defrecord GameActionInfo [user team score timestamp])

(lan/def-record-schema game-action-info-schema
  [:user lan/string-schema]
  [:team lan/string-schema]
  [:score lan/int-schema]
  [:timestamp lan/long-schema])

(def ^:private game-action-info-coder-impl
  (proxy [CustomCoder] []
    (encode [val ^OutputStream out]
      (let [record-bytes ^"[B" (lan/serialize game-action-info-schema val)
            size (count record-bytes)]
        (.write out (-> (ByteBuffer/allocate 4) (.putInt size) (.array)))
        (.write out record-bytes)))
    (decode [^InputStream in]
      (let [size-bytes (byte-array 4)
            _ (.read in size-bytes)
            size (.getInt (ByteBuffer/wrap size-bytes))
            record-bytes (byte-array size)
            _ (.read in record-bytes)]
        (lan/deserialize-same game-action-info-schema record-bytes)))))

(def game-action-info-coder
  (TCoder. #'game-action-info-coder-impl))

(defn- ^{:th/coder game-action-info-coder} parse-event [^String elem]
  (try
    (let [[user team score ts :as parts] (.split elem "," -1)]
      (if (>= (alength parts) 4)
        (->GameActionInfo
          (.trim ^String user)
          (.trim ^String team)
          (Integer/parseInt (.trim ^String score))
          (Long/parseLong (.trim ^String ts)))
        (log/warnf "parse error on %s, missing part" elem)))
    (catch NumberFormatException e
      (log/warnf "parse error on %s, %s" elem (.getMessage e)))))

(def ^:private kv-string-int-coder
  (KvCoder/of (StringUtf8Coder/of) (VarIntCoder/of)))

(defn- ^{:th/coder kv-string-int-coder} ->field-and-score-kv [field elem]
  (KV/of (field elem) (:score elem)))

(defn ->extract-sum-and-score-xf [field]
  (th/compose "extract-sum-and-score"
    (th/partial #'->field-and-score-kv field)
    (Sum/integersPerKey)))

(defn- ^{:th/coder (StringUtf8Coder/of)} format-row [^KV kv]
  (format "user: %s, total_score: %d" (.getKey kv) (.getValue kv)))

(defn- ->write-to-text-xf [output row-formatter]
  (th/compose "write-to-text"
    row-formatter
    (-> (TextIO/write)
      (.to ^String output))))

(defn- create-pipeline [opts]
  (let [pipeline (th/create-pipeline opts)
        conf (th/get-custom-config pipeline)]
    (doto pipeline
      (th/apply!
        (-> (TextIO/read)
          (.from ^String (:input conf)))
        #'parse-event
        (->extract-sum-and-score-xf :user)
        (->write-to-text-xf (:output conf) #'format-row)))))

(defn demo! [& args]
  (-> (create-pipeline
        (concat
          args
          (th/->beam-args
            {:custom-config
             {:input "gs://apache-beam-samples/game/gaming_data*.csv"
              :output "gs://thurber-demo/user-score-opt-"}})))
    (.run)))
