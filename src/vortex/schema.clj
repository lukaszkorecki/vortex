(ns vortex.schema
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.json-schema :as mjs]))

(def schema? m/schema?)
(def ->schema m/schema)

(defn ->json-schema-for-inference
  "Transforms a Malli schema into a JSON Schema suitable for use with
  Gemini/Vertex inference, updating the `:type` keyword to match what
  the Gemini API expects: all-caps type names."
  [schema & {:keys [all-required?] :or {all-required? false}}]
  (->> schema
       (walk/postwalk (fn [thing]
                        ;; ensure :optional keys are removed if all-required? is true
                        ;; only applicable for schemas meant for schemas used in inference inputs
                        (if (and all-required? (map? thing) (:optional thing))
                          (dissoc thing :optional)
                          thing)))
       (m/schema)
       (mjs/transform)
       (walk/postwalk (fn [thing]
                        ;; {:type "string" ...} => {:type "STRING" ...}
                        (if (and (map? thing) (:type thing))
                          (update thing :type str/upper-case)
                          thing)))))
