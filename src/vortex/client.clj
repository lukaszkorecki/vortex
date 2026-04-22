(ns vortex.client
  (:require
   [cheshire.core :as json]
   [com.stuartsierra.component :as component]
   [vortex.schema :as schema])
  (:import
   [com.google.genai Client Models]
   [com.google.genai.types AutoValue_GenerateContentResponse GenerateContentConfig]))

(set! *warn-on-reflection* true)

(defn ->config [{:keys [system-instruction response-schema]}]
  (GenerateContentConfig/fromJson
   ^String (json/generate-string
            {:temperature 0.0
             :responseMimeType "application/json"
             :responseSchema response-schema
             :systemInstruction {:role "user"
                                 :parts [{:text system-instruction}]}})))

(defn ->client [{:keys [project location]}]
  (.build (doto (Client/builder)
            (.project project)
            (.location location)
            (.vertexAI true))))

(defn generate-content* [^Client client {:keys [model config input]}]
  (let [m (.models client)
        response (Models/.generateContent m ^String model ^String input ^GenerateContentConfig config)
        response-str (AutoValue_GenerateContentResponse/.text response)]
    (when response-str
      (json/parse-string response-str true))))

(defprotocol IGenAI
  (generate-content [client input]))

(defrecord GenAIClient [;; provided
                        project location system-instruction response-schema model
                         ;; internal state
                        client
                        gen-config]
  component/Lifecycle
  (start [this]
    (if client
      this
      (let [client (->client {:project project :location location})
            gen-config (->config {:system-instruction system-instruction
                                  :response-schema response-schema})]
        (assoc this :client client :gen-config gen-config))))
  (stop [this]
    (if client
      (do
        (Client/.close client)
        (assoc this :client nil :gen-config nil))
      this))

  IGenAI
  (generate-content [_this input]
    (let [result (generate-content* client {:config gen-config
                                            :model model
                                            :input input})]
      (reduce-kv (fn [acc k v] (if v (assoc acc k v) acc)) {} result))))

(defn create [{:keys [project location model system-instruction response-schema]}]
  (let [resp-schema (if (schema/schema? response-schema)
                      (schema/->json-schema-for-inference response-schema)
                      (schema/->json-schema-for-inference (schema/->schema response-schema)))]
    (map->GenAIClient {:project project
                       :location location
                       :model model
                       :system-instruction system-instruction
                       :response-schema resp-schema})))
