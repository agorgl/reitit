(ns reitit.coercion.schema
  (:require [clojure.set :as set]
            [clojure.walk :as walk]
            [reitit.coercion :as coercion]
            [schema-tools.coerce :as stc]
            [schema-tools.core :as st]
            [schema-tools.openapi.core :as openapi]
            [schema-tools.swagger.core :as swagger]
            [schema.coerce :as sc]
            [schema.core :as s]
            [schema.utils :as su]))

(def string-coercion-matcher
  stc/string-coercion-matcher)

(def json-coercion-matcher
  stc/json-coercion-matcher)

(def default-coercion-matcher
  (constantly nil))

(defmulti coerce-response? identity :default ::default)
(defmethod coerce-response? ::default [_] true)

(defn stringify [schema]
  (walk/prewalk
   (fn [x]
     (cond
       #?@(:clj [(class? x) (.getName ^Class x)])
       (instance? schema.core.OptionalKey x) (pr-str (list 'opt (:k x)))
       (instance? schema.core.RequiredKey x) (pr-str (list 'req (:k x)))
       (and (satisfies? s/Schema x) (record? x)) (try (pr-str (s/explain x)) (catch #?(:clj Exception :cljs js/Error) _ x))
       (instance? schema.utils.ValidationError x) (str (su/validation-error-explain x))
       (instance? schema.utils.NamedError x) (str (su/named-error-explain x))
       :else x))
   schema))

(def default-options
  {:coerce-response? coerce-response?
   :matchers {:body {:default default-coercion-matcher
                     :formats {"application/json" json-coercion-matcher}}
              :string {:default string-coercion-matcher}
              :response {:default default-coercion-matcher}}})

(defn create [{:keys [matchers coerce-response?] :as opts}]
  ^{:type ::coercion/coercion}
  (reify coercion/Coercion
    (-get-name [_] :schema)
    (-get-options [_] opts)
    (-get-model-apidocs [_ specification model options]
      (case specification
        :openapi (openapi/transform model (merge opts options))
        (throw
         (ex-info
          (str "Can't produce Schema apidocs for " specification)
          {:type specification, :coercion :schema}))))
    (-get-apidocs [_ specification {:keys [request parameters responses content-types]
                                    :or {content-types ["application/json"]}}]
     ;; TODO: this looks identical to spec, refactor when schema is done.
      (case specification
        :swagger (swagger/swagger-spec
                  (merge
                   (if parameters
                     {::swagger/parameters parameters})
                   (if responses
                     {::swagger/responses
                      (into
                       (empty responses)
                       (for [[k response] responses]
                         [k (-> response
                                (dissoc :content)
                                (set/rename-keys {:body :schema}))]))})))
        ;; :openapi handled in reitit.openapi/-get-apidocs-openapi
        (throw
         (ex-info
          (str "Can't produce Schema apidocs for " specification)
          {:type specification, :coercion :schema}))))
    (-compile-model [_ model _]
      (if (= 1 (count model))
        (first model)
        (apply st/merge model)))
    (-open-model [_ schema] (st/open-schema schema))
    (-encode-error [_ error]
      (-> error
          (update :schema stringify)
          (update :errors stringify)))
    (-request-coercer [_ type schema]
      (let [{:keys [formats default]} (matchers type)
            coercers (->> (for [m (conj (vals formats) default)]
                            [m (sc/coercer schema m)])
                          (into {}))]
        (fn [value format]
          (if-let [matcher (or (get formats format) default)]
            (let [coercer (coercers matcher)
                  coerced (coercer value)]
              (if-let [error (su/error-val coerced)]
                (coercion/map->CoercionError
                 {:schema schema
                  :errors error})
                coerced))
            value))))
    (-response-coercer [this schema]
      (if (coerce-response? schema)
        (coercion/-request-coercer this :response schema)))
    (-query-string-coercer [this schema]
      nil)))

(def coercion (create default-options))
