(ns vortex.client-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.stuartsierra.component :as component]
   [malli.core :as m]
   [matcher-combinators.test :refer [match?]]
   [vortex.client :as client]))

(deftest create-test
  (testing "accepts a raw Malli schema vector and compiles it"
    (let [c (client/create {:project "p" :location "us-central1"
                            :model "gemini-2.0-flash"
                            :system-instruction "be helpful"
                            :response-schema [:map [:name :string]]})]
      (is (instance? vortex.client.GenAIClient c))
      (is (match? {:type "OBJECT"
                   :properties {:name {:type "STRING"}}}
                  (:response-schema c)))))

  (testing "accepts an already-compiled Malli schema"
    (let [c (client/create {:project "p" :location "us-central1"
                            :model "gemini-2.0-flash"
                            :system-instruction "be helpful"
                            :response-schema (m/schema [:map [:age :int]])})]
      (is (match? {:type "OBJECT"
                   :properties {:age {:type "INTEGER"}}}
                  (:response-schema c))))))

(deftest lifecycle-idempotency-test
  (testing "stop on an unstarted component is a no-op"
    (let [c (client/create {:project "p" :location "us-central1"
                            :model "gemini-2.0-flash"
                            :system-instruction "x"
                            :response-schema [:map [:a :string]]})
          stopped (component/stop c)]
      (is (nil? (:client stopped))))))
