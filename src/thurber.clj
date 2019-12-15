(ns thurber
  (:require [camel-snake-kebab.core :as csk]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [taoensso.nippy :as nippy]
            [clojure.tools.logging :as log])
  (:import (org.apache.beam.sdk.transforms PTransform Create ParDo DoFn$ProcessContext Count SerializableFunction Combine SerializableBiFunction DoFn$OnTimerContext)
           (java.util Map)
           (thurber.java TDoFn TCoder TOptions TSerializableFunction TProxy TCombine TSerializableBiFunction TDoFn_Stateful)
           (org.apache.beam.sdk.values PCollection KV PCollectionView TupleTag TupleTagList PCollectionTuple)
           (org.apache.beam.sdk Pipeline)
           (org.apache.beam.sdk.options PipelineOptionsFactory PipelineOptions)
           (clojure.lang MapEntry)
           (org.apache.beam.sdk.transforms.windowing BoundedWindow)
           (org.apache.beam.sdk.coders KvCoder CustomCoder)
           (java.io DataInputStream InputStream DataOutputStream OutputStream)
           (org.apache.beam.sdk.state ValueState Timer)))

;; --

(def ^:private nippy-impl
  (proxy [CustomCoder] []
    (encode [val ^OutputStream out]
      (nippy/freeze-to-out! (DataOutputStream. out) val))
    (decode [^InputStream in]
      (nippy/thaw-from-in! (DataInputStream. in)))))

(def nippy
  (TCoder. #'nippy-impl))

(def nippy-kv (KvCoder/of nippy nippy))

;; nippy codes MapEntry as vectors by default; but we want them to stay
;; MapEntry after thaw:

(nippy/extend-freeze
  MapEntry :thurber/map-entry
  [val data-output]
  (let [[k v] val]
    (nippy/freeze-to-out! data-output [k v])))

(nippy/extend-thaw
  :thurber/map-entry
  [data-input]
  (let [[k v] (nippy/thaw-from-in! data-input)]
    (MapEntry/create k v)))

;; --

(def ^:dynamic ^PipelineOptions *pipeline-options* nil)
(def ^:dynamic ^DoFn$ProcessContext *process-context* nil)
(def ^:dynamic ^BoundedWindow *element-window* nil)
(def ^:dynamic ^ValueState *value-state* nil)
(def ^:dynamic ^Timer *event-timer* nil)
(def ^:dynamic ^DoFn$OnTimerContext *timer-context* nil)

(def ^:dynamic *proxy-args* nil)

;; --

(defn apply**
  ([fn
    ^PipelineOptions options
    ^DoFn$ProcessContext context
    ^BoundedWindow window
    args-array]
   (apply** fn options context window nil nil args-array))
  ([fn
    ^PipelineOptions options
    ^DoFn$ProcessContext context
    ^BoundedWindow window
    ^ValueState state
    ^Timer timer
    args-array]
   (binding [*pipeline-options* options
             *process-context* context
             *element-window* window
             *value-state* state
             *event-timer* timer]
     (when-let [rv (apply fn (concat args-array [(.element context)]))]
       (if (seq? rv)
         (doseq [v rv]
           (.output context v))
         (.output context rv))))))

(defn apply-timer**
  [timer-fn ^DoFn$OnTimerContext context ^ValueState state]
  (binding [*value-state* state
            *timer-context* context]
    (let [rv (timer-fn)]
      (when (some? rv)
        (throw (RuntimeException.
                 "for now, output from timer func must be imperative"))))))

;; --

(defn proxy-with-signature* [proxy-var sig & args]
  (TProxy/create proxy-var sig (into-array Object args)))

(defn proxy* [proxy-var & args]
  (proxy-with-signature* proxy-var nil args))

;; --

(defn ->beam-args [m]
  (map (fn [[k v]]
         (format "--%s=%s"
           (-> k csk/->camelCase name)
           (cond
             (map? v) (json/write-str v)
             (coll? v) (str/join "," v)
             :else (-> v str (str/escape {\" "\\\""}))))) m))

(defn ^PipelineOptions create-options
  ([]
   (create-options [] TOptions))
  ([opts]
   (create-options opts TOptions))
  ([opts as]
   (-> (PipelineOptionsFactory/fromArgs
         (cond
           (map? opts) (into-array String (->beam-args opts))
           (coll? opts) (into-array String opts)
           :else opts))
     (.as as))))

(defn ^Pipeline create-pipeline
  ([] (Pipeline/create))
  ([opts] (-> (if (instance? PipelineOptions opts)
                opts (create-options opts))
            (Pipeline/create))))

(defn get-custom-config [obj]
  (if (instance? Pipeline obj)
    (recur (.getOptions obj))
    (->> (.getCustomConfig ^TOptions (.as obj TOptions))
      (into {}) walk/keywordize-keys)))

;; --

(defn- var->name [v]
  (or (:th/name (meta v)) (:name (meta v)))
  (-> v meta :name name))

(defn ^PTransform partial*
  [fn-var-or-name & args]
  (if (string? fn-var-or-name)
    {:th/name fn-var-or-name
     :th/xform (first args)
     :th/params (rest args)}
    {:th/name (format "partial*/%s" (var->name fn-var-or-name))
     :th/xform fn-var-or-name
     :th/params args}))

(defn- filter-impl [pred-fn & args]
  (when (apply pred-fn args)
    (last args)))

(defn ^PTransform filter* [pred-var-or-name & args]
  (if (string? pred-var-or-name)
    {:th/name pred-var-or-name
     :th/xform #'filter-impl
     :th/params args}
    {:th/name (format "filter*/%s" (var->name pred-var-or-name))
     :th/xform #'filter-impl
     :th/params (conj args pred-var-or-name)}))

(defn ^SerializableFunction simple* [fn-var & args]
  (TSerializableFunction. fn-var args))

(defn ^SerializableBiFunction simple-bi* [fn-var & args]
  (TSerializableBiFunction. fn-var args))

;; --

(defn- ^TCoder ->explicit-coder* [prev nxf]
  (when-let [c (:th/coder nxf)]
    (if (= c :th/inherit)
      (.getCoder prev) c)))

(defn- ->pardo [xf params stateful? timer-fn]
  (let [tags (into [] (filter #(instance? TupleTag %) params))
        views (into [] (filter #(instance? PCollectionView %)) params)]
    (cond-> (ParDo/of (if (or stateful? timer-fn)
                        (TDoFn_Stateful. xf timer-fn (object-array params))
                        (TDoFn. xf (object-array params))))
      (not-empty tags)
      (.withOutputTags ^TupleTag (first tags)
        (reduce (fn [^TupleTagList acc ^TupleTag tag]
                  (.and acc tag)) (TupleTagList/empty) (rest tags)))
      (not-empty views)
      (.withSideInputs
        ^Iterable (into [] (filter #(instance? PCollectionView %)) params)))))

(defn- set-coder! [pcoll-or-tuple coder]
  (cond
    (instance? PCollection pcoll-or-tuple) (.setCoder ^PCollection pcoll-or-tuple coder)
    (instance? PCollectionTuple pcoll-or-tuple) (do
                                                  (->> ^PCollectionTuple pcoll-or-tuple
                                                    (.getAll)
                                                    (.values)
                                                    (run! #(.setCoder ^PCollection % coder)))
                                                  pcoll-or-tuple)))

(defn- ->normal-xf*
  ([xf] (->normal-xf* xf {}))
  ([xf override]
   (cond
     (instance? PTransform xf) (merge {:th/xform xf} override)
     (map? xf) (->normal-xf* (:th/xform xf) (merge (dissoc xf :th/xform) override)) ;; note: maps may nest.
     (var? xf) (let [normal (merge {:th/name (var->name xf) :th/coder nippy}
                                   (select-keys (meta xf) [:th/name :th/coder :th/params :th/stateful]) override)]
                 (assoc normal :th/xform (->pardo xf (:th/params normal) (:th/stateful normal)
                                           (:th/timer-fn normal)))))))

(defn ^PCollection apply!
  "Apply transforms to an input (Pipeline, PCollection, PBegin ...)"
  [input & xfs]
  (reduce
   (fn [acc xf]
     (let [nxf (->normal-xf* xf)
           ;; Take care here. acc' may commonly be PCollection but can also be
           ;;    PCollectionTuple or PCollectionView, eg.
           acc' (if (:th/name nxf)
                  (.apply acc (:th/name nxf) (:th/xform nxf))
                  (.apply acc (:th/xform nxf)))
           explicit-coder (->explicit-coder* acc nxf)]
       (when explicit-coder
         (set-coder! acc' explicit-coder)) acc')) input xfs))

(defn ^PTransform comp* [& [xf-or-name :as xfs]]
  (proxy [PTransform] [(when (string? xf-or-name) xf-or-name)]
    (expand [^PCollection pc]
      (apply apply! pc (if (string? xf-or-name) (rest xfs) xfs)))))

;; --

(defn ^PTransform create [coll]
  (if (map? coll)
    (-> (Create/of ^Map coll) (.withCoder nippy))
    (-> (Create/of ^Iterable (seq coll)) (.withCoder nippy))))

;; --

(defprotocol CombineFn
  (create-accumulator [this])
  (add-input [this acc input])
  (merge-accumulators [this acc-coll])
  (extract-output [this acc]))

(defmacro def-combiner [& body]
  `(reify CombineFn
     ~@body))

(defn- combiner* [xf-var]
  (let [xf (deref xf-var)]
    (cond
      (satisfies? CombineFn xf) (TCombine. xf-var)
      (fn? xf) (simple-bi* xf-var))))

(defn combine-globally [xf-var]
  {:th/name (var->name xf-var)
   :th/xform (Combine/globally (combiner* xf-var))})

(defn combine-per-key [xf-var]
  {:th/name (var->name xf-var)
   :th/xform (Combine/perKey (combiner* xf-var))})

;; --

(defn ^{:th/coder nippy-kv} ->kv
  ([seg]
   (KV/of seg seg))
  ([key-fn seg]
   (KV/of (key-fn seg) seg))
  ([key-fn val-fn seg]
   (KV/of (key-fn seg) (val-fn seg))))

;; --

(defn kv->clj [^KV kv]
  (MapEntry/create (.getKey kv) (.getValue kv)))

;; --

(defn log-elem*
  ([elem] (log/logp :info elem))
  ([level elem] (log/logp level elem)))

;; --

(defn count-per-key
  ([] (count-per-key "count-per-key"))
  ([xf-name] (comp* xf-name
               (Count/perKey)
               #'kv->clj)))