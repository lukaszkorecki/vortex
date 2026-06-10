(ns vortex.client
  (:require
   [cheshire.core :as json]
   [com.stuartsierra.component :as component]
   [vortex.schema :as schema])
  (:import
   [com.google.genai Client Models]
   [com.google.genai.types Content AutoValue_GenerateContentResponse GenerateContentConfig GenerateContentResponse]))

(set! *warn-on-reflection* true)

(defn ->config [{:keys [system-instruction response-schema]}]
  (GenerateContentConfig/fromJson
   ^String (json/generate-string (cond-> {:temperature 0.0}
                                   response-schema (assoc :responseSchema response-schema
                                                          :responseMimeType "application/json")
                                   system-instruction (assoc
                                                       :systemInstruction {:role "user"
                                                                           :parts [{:text system-instruction}]})))))

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

(defn ->content ^Content [{:keys [role text]}]
  (Content/fromJson (json/generate-string {:role role :parts [{:text text}]})))

(defn history->contents
  "Convert a persisted history vec into a java.util.List<Content>."
  ^java.util.List
  [history]
  (java.util.ArrayList. (mapv ->content history)))

(defn send-message* [^Client client {:keys [model config
                                            ;;provided
                                            context history message]}]
  (let [m (.models client)
        all-history (conj (if (seq history)
                            ;; we have history - pass it around
                            (vec history)
                            ;; blank history, include context first, then first message
                            [{:role "user"
                              :text (format "<PRIVATE CONTEXT>\n# Context for this conversation\n\n%s\n\n</PRIVATE CONTEXT>" context)}])
                          {:role "user" :text message})
        contents (history->contents all-history)
        response (Models/.generateContent m ^String model
                                          ^java.util.ArrayList contents
                                          ^GenerateContentConfig config)
        reply (GenerateContentResponse/.text response)]

    {:reply reply
     :history (conj all-history {:role "model" :text reply})}))

(defprotocol IGenAI
  (generate-content [client input]
    "One-shot generate content using the client, with pre-baked system instructions and schema")
  ;; TBC/TBD
  (send-message [client {:keys [message history context]}]
    "Send message to a chat session, requires history, chat context"))

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
      (reduce-kv (fn [acc k v] (if v (assoc acc k v) acc)) {} result)))

  (send-message [_this {:keys [message history context]}]
    (send-message* client {:config gen-config
                           :model model
                           ;; provided
                           :context context
                           :message message
                           :history history})))

(defn create [{:keys [project location model system-instruction response-schema]}]
  (let [resp-schema (when response-schema
                      (if (schema/schema? response-schema)
                        (schema/->json-schema-for-inference response-schema)
                        (schema/->json-schema-for-inference (schema/->schema response-schema))))]
    (map->GenAIClient {:project project
                       :location location
                       :model model
                       :system-instruction system-instruction
                       :response-schema resp-schema})))
