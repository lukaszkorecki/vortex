(ns vortex.schema-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [matcher-combinators.test :refer [match?]]
   [vortex.schema :as schema]))

(deftest json-schema-for-inference-test
  (testing "transforms simple schema to JSON with uppercased types"
    (let [result (schema/->json-schema-for-inference
                  [:map
                   [:name :string]
                   [:age :int]])]
      (is (match? {:type "OBJECT"
                   :properties {:name {:type "STRING"}
                                :age {:type "INTEGER"}}}
                  result))))

  (testing "handles optional fields"
    (let [result (schema/->json-schema-for-inference
                  [:map
                   [:name :string]
                   [:nickname {:optional true} :string]])]
      (is (some? (:properties result)))
      (is (contains? (:properties result) :name))
      (is (contains? (:properties result) :nickname))))

  (testing "all-required? strips optional markers"
    (let [result (schema/->json-schema-for-inference
                  [:map
                   [:name :string]
                   [:nickname {:optional true} :string]]
                  :all-required? true)]
      (is (match? {:required [:name :nickname]}
                  result))))

  (testing "works with nested/nullable schemas"
    (let [result (schema/->json-schema-for-inference
                  (schema/->schema
                   [:map
                    [:cfm {:optional true :json-schema/nullable true}
                     [:vector :int]]
                    [:color {:optional true}
                     [:enum "White" "Black"]]]))]
      (is (match? {:type "OBJECT"
                   :properties {:cfm {:type "ARRAY"}
                                :color {}}}
                  result)))))

(deftest schema-predicates-test
  (testing "schema? returns true for Malli schemas"
    (is (true? (schema/schema? (schema/->schema [:map [:a :string]])))))

  (testing "schema? returns false for non-schemas"
    (is (false? (schema/schema? {:not "a schema"})))))
