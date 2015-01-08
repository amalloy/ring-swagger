(ns ring.swagger.swagger2
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [ring.util.response :refer :all]
            [ring.swagger.impl :refer :all]
            [schema.core :as s]
            [plumbing.core :refer :all :exclude [update]]
            ring.swagger.json
            ;; needed for the json-encoders
            [ring.swagger.common :refer :all]
            [ring.swagger.json-schema :as jsons]
            [org.tobereplaced.lettercase :as lc]
            [ring.swagger.swagger2-schema :as schema]
            [instar.core :as instar])
  (:import (clojure.lang Sequential IPersistentSet)))

(def Anything {s/Keyword s/Any})
(def Nothing {})

;;
;; 2.0 Json Schema changes
;;

(defn ->json [& args]
  (binding [jsons/*swagger-spec-version* "2.0"]
    (apply jsons/->json args)))

(defn properties [schema]
  (binding [jsons/*swagger-spec-version* "2.0"]
    (jsons/properties schema)))

;;
;; Schema transformations
;;

;; COPY from 1.2
(defn- full-name [path] (->> path (map name) (map lc/capitalized) (apply str) symbol))

;; COPY from 1.2
(defn- collect-schemas [keys schema]
  (cond
    (plain-map? schema)
    (if (and (seq (pop keys)) (s/schema-name schema))
      schema
      (with-meta
        (into (empty schema)
              (for [[k v] schema
                    :when (jsons/not-predicate? k)
                    :let [keys (conj keys (s/explicit-schema-key k))]]
                [k (collect-schemas keys v)]))
        {:name (full-name keys)}))

    (valid-container? schema)
    (contain schema (collect-schemas keys (first schema)))

    :else schema))

;; COPY from 1.2
(defn with-named-sub-schemas
  "Traverses a schema tree of Maps, Sets and Sequences and add Schema-name to all
   anonymous maps between the root and any named schemas in thre tree. Names of the
   schemas are generated by the following: Root schema name (or a generated name) +
   all keys in the path CamelCased"
  [schema]
  (collect-schemas [(or (s/schema-name schema) (gensym "schema"))] schema))

(defn extract-models [swagger]
  (let [route-meta      (->> swagger
                             :paths
                             vals
                             (map vals)
                             flatten)
        body-models     (->> route-meta
                             (map (comp :body :parameters)))
        response-models (->> route-meta
                             (map :responses)
                             (mapcat vals)
                             (map :schema))]
    (->> (concat body-models response-models)
         flatten
         (map with-named-sub-schemas)
         (map (juxt s/schema-name identity))
         (into {})
         vals)))

(defn transform [schema]
  (let [required (required-keys schema)
        required (if-not (empty? required) required)]
    (remove-empty-keys
      {:properties (properties schema)
       :required required})))

; COPY from 1.2
(defn collect-models [x]
  (let [schemas (atom {})]
    (walk/prewalk
      (fn [x]
        (when-let [schema (and
                            (plain-map? x)
                            (s/schema-name x))]
          (swap! schemas assoc schema (if (var? x) @x x)))
        x)
      x)
    @schemas))

(defn transform-models [schemas]
  (->> schemas
       (map collect-models)
       (apply merge)
       (map (juxt (comp keyword key) (comp transform val)))
       (into {})))

;;
;; Paths, parameters, responses
;;

(defmulti ^:private extract-body-parameter
  (fn [e]
    (if (instance? Class e)
      e
      (class e))))

(defmethod extract-body-parameter Sequential [e]
  (let [model (first e)
        schema-json (->json model)]
    (vector {:in          :body
             :name        (name (s/schema-name model))
             :description (or (:description schema-json) "")
             :required    true
             :schema      {:type  "array"
                           :items (dissoc schema-json :description)}})))

(defmethod extract-body-parameter IPersistentSet [e]
  (let [model (first e)
        schema-json (->json model)]
    (vector {:in          :body
             :name        (name (s/schema-name model))
             :description (or (:description schema-json) "")
             :required    true
             :schema      {:type        "array"
                           :uniqueItems true
                           :items       (dissoc schema-json :description)}})))

(defmethod extract-body-parameter :default [model]
  (if-let [schema-name (s/schema-name model)]
    (let [schema-json (->json model)]
      (vector {:in          :body
               :name        (name schema-name)
               :description (or (:description schema-json) "")
               :required    true
               :schema      (dissoc schema-json :description)}))))

(defmulti ^:private extract-parameter first)

(defmethod extract-parameter :body [[_ model]]
  (extract-body-parameter model))

(defmethod extract-parameter :default [[type model]]
  (if model
    (for [[k v] (-> model value-of strict-schema)
          :when (s/specific-key? k)
          :let [rk (s/explicit-schema-key k)]]
      (jsons/->parameter {:in type
                          :name (name rk)
                          :required (s/required-key? k)}
                         (->json v)))))

(defn convert-parameters [parameters]
  (into [] (mapcat extract-parameter parameters)))

(defn convert-responses [responses]
  (let [convert (fn [schema]
                  (if-let [json-schema (->json schema)]
                    json-schema
                    (transform schema)))
        responses (for-map [[k v] responses
                            :let [{:keys [schema headers description]} v]]
                    k (merge v
                             (when schema
                               {:schema (convert schema)})
                             (when headers
                               {:headers (properties headers)})))]
    (if-not (empty? responses)
      responses
      {:default {:description "" :schema s/Any}})))

(defn transform-operation
    "Returns a map with methods as keys and the Operation
     maps with parameters and responses transformed to comply
     with Swagger JSON spec as values"
    [operation]
    (for-map [[k v] operation]
      k (-> v
            (update-in-or-remove-key [:parameters] convert-parameters empty?)
            (update-in [:responses] convert-responses))))

(defn swagger-path [uri]
  (str/replace uri #":([^/]+)" "{$1}"))

(defn extract-paths-and-definitions [swagger]
  (let [paths (->> swagger
                   :paths
                   (reduce-kv (fn [acc k v]
                                (assoc acc
                                  (swagger-path k)
                                  (transform-operation v))) {}))
        definitions (->> swagger
                         extract-models
                         transform-models)]
    [paths definitions]))

;;
;; Named top level schemas in body parameters and responses
;;

(defn direct-or-contained [f x]
  (if (valid-container? x) (f (first x)) (f x)))

(defn ensure-model-schema-name [model prefix]
  (if-not (or (direct-or-contained s/schema-name model)
              (direct-or-contained (comp not map?) model))
    (update-schema model (fn-> (s/schema-with-name (gensym (or prefix "Model")))))
    model))

(defn ensure-named-top-level-models
  "Takes a ring-swagger spec and returns a new version
   with a generated name added for all the top level maps
   that come as body parameters or response models and are
   not named schemas already"
  [swagger]
  (let [swagger (instar/transform swagger
                                  [:paths * * :parameters :body]
                                  (fn-> (ensure-model-schema-name "Body")))
        swagger (instar/transform swagger
                                  [:paths * * :responses * :schema]
                                  (fn-> (ensure-model-schema-name "Response")))]
    swagger))

;;
;; Schema
;;

(def swagger-defaults {:swagger  "2.0"
                       :info     {:title "Swagger API"
                                  :version "0.0.1"}
                       :produces ["application/json"]
                       :consumes ["application/json"]})

;;
;; Public API
;;

(def Swagger schema/Swagger)

(s/defn swagger-json
  "produces the swagger-json from ring-swagger spec"
  [swagger :- Swagger]
  (let [[paths definitions] (-> swagger
                                ensure-named-top-level-models
                                extract-paths-and-definitions)]
    (merge
      swagger-defaults
      (-> swagger
          (assoc :paths paths)
          (assoc :definitions definitions)))))
