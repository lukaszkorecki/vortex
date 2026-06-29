;; FIXME: migrate to using API directly, and get rid of Java SDK for GenAI
;; use only oauth-client lib from Google to streamline authentication for dynamic creds
(ns vortex.client
  (:require
   [cheshire.core :as json]
   [com.stuartsierra.component :as component]
   [vortex.schema :as schema])
  (:import
   [com.google.genai Client Models]
   [com.google.genai.types Content GenerateContentConfig GenerateContentResponse]
   [java.util ArrayList Collection]))

(set! *warn-on-reflection* true)

(defn ->config
  "Create config for content generation. Options:
  - `:system-instruction` - string with default system instructions
    attached to the client for every requst
  - `:response-schema` - JSON schema for the response, intended
  for single-shot mode. See `vortex.schema` for converting Malli schemas to
  GenAI's format"
  [{:keys [system-instruction response-schema]}]
  (let [config (cond-> {:temperature 0.0} ;; TODO: make temperature tweakable?
                 response-schema (assoc :responseSchema response-schema
                                        :responseMimeType "application/json")
                 system-instruction (assoc :systemInstruction {:role "user"
                                                               :parts [{:text system-instruction}]}))]
    (GenerateContentConfig/fromJson ^String (json/generate-string config))))

(defn ->client [{:keys [project location]}]
  (.build (doto (Client/builder)
            (.project project)
            (.location location)
            (.vertexAI true))))

;; --- SDK boundary -----------------------------------------------------------
;; These two fns are the only place we touch `Models/.generateContent`. They
;; exist so tests can `with-redefs` them to intercept the request that would be
;; sent and stub the model's response (see `vortex.client-test`). Keep them
;; dumb: no input construction or output processing belongs here.

(defn invoke-generate
  "One-shot SDK call. Sends `input` (a String) and returns the raw
  `GenerateContentResponse`."
  ^GenerateContentResponse
  [^Client client {:keys [^String model
                          ^String input
                          ^GenerateContentConfig config]}]
  (Models/.generateContent (.models client) model input config))

(defn invoke-chat
  "Multi-turn SDK call. Sends `contents` (a List<Content>) and returns the raw
  `GenerateContentResponse`."
  ^GenerateContentResponse
  [^Client client {:keys [^String model
                          ^java.util.List contents
                          ^GenerateContentConfig config]}]
  (Models/.generateContent (.models client) model contents config))



(defn generate-content* [^Client client {:keys [model config input]}]
  (let [response (invoke-generate client {:model model :input input :config config})
        response-str (GenerateContentResponse/.text response)]
    (when response-str
      (->> (json/parse-string response-str true)
           (reduce-kv (fn [acc k v]
                        (if (some? v)
                          (assoc acc k v)
                          acc))
                      {})))))

;; FIXME: content handling is really weird because Java API forces using 'text' field for storing response parts
;; but they don't have to be text content... because of structured output schema.
(defn ->content ^Content [{:keys [role text]}]
  (when (and role text)
    (Content/fromJson (json/generate-string {:role role
                                             :parts [{:text (if (string? text)
                                                              text
                                                              (json/generate-string text))}]}))))

(defn history->contents
  "Convert a persisted history vec into a java.util.List<Content>."
  ^java.util.List
  [history]
  (ArrayList. ^Collection (filterv some? (mapv ->content history))))

(defn context->message [context]
  (when context
    {:role "user"
     :text (format "<PRIVATE CONTEXT>\n# Context for this conversation\n\n%s\n\n</PRIVATE CONTEXT>" context)}))

(defn send-message* [^Client client {:keys [model config
                                            ;; provided
                                            context history message
                                            ;; output handling
                                            structured-responses?]}]
  (let [history-so-far (->> (if (seq history)
                              ;; we have history - pass it around
                              history
                              ;; blank history, include context first, if provided
                              [(context->message context)])
                            ;; ensure we don't have `nil`s
                            ;; NOTE: that filterv is important
                            ;; because we want conj to append user message
                            (filterv some?))
        all-history (conj history-so-far {:role "user" :text message})
        contents (history->contents all-history)
        response (invoke-chat client {:model model :contents contents :config config})
        raw-reply (GenerateContentResponse/.text response)
        reply (if structured-responses?
                (json/parse-string raw-reply true)
                raw-reply)
        finish-reason (str (GenerateContentResponse/.finishReason response))
        function-calls (GenerateContentResponse/.functionCalls response)]

    {:reply reply
     :raw-reply raw-reply
     :finish-reason finish-reason
     :function-calls (vec function-calls)
     :history (conj all-history {:role "model" :text reply})}))

(defprotocol IGenAI
  (generate-content [client {:keys [input]}]
    "One-shot generate content using the client, with pre-baked system instructions and schema")
  ;; TBC/TBD
  (send-message [client {:keys [message history context]}]
    "Send message to a chat session, requires history, chat context"))

(defrecord GenAIClient [;; provided
                        project location system-instruction response-schema model
                        ;; internal state
                        client
                        gen-config
                        structured-response?]
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
  (generate-content [_this {:keys [input]}]
    (generate-content* client {:config gen-config
                               :model model
                               :input input}))

  (send-message [_this {:keys [message history context]}]
    (send-message* client {:config gen-config
                           :model model
                           ;; provided
                           :context context
                           :message message
                           :history history
                           :structured-responses? true})))

(defn create
  "Create a GenAI (aka Vertex aka Gemini on Vertex) client.
  Required opts:
  - `project` - your GCP project
  - `model` - model name, as per API docs e.g `gemini-2.5-flash`

  Optional:
  - `system-instruction` - system instructions for the
  model. Set global instructions for all uses of the model
  - `response-schema` - forces the model to reply using structured data,
  uses Malli schema for definition

   NOTE: response schema forces the model to reply using JSON. This is
  required for `generate-content` (one-shot structured data) and also works
  for `send-message` (structured replies on every chat turn). When a schema
  is set, `send-message` parses each reply and stores the resulting map under
  the `:text` key of the `model` history turn - so for chat-with-schema
  `:text` is an object, not a string. See the README for details. Omit
  `response-schema` if you want plain-text chat replies.
  NOTE: chat doesn't support tool calling or other
  grounding features yet."
  [{:keys [project location model system-instruction response-schema]}]
  (let [resp-schema (when response-schema
                      (if (schema/schema? response-schema)
                        (schema/->json-schema-for-inference response-schema)
                        (schema/->json-schema-for-inference (schema/->schema response-schema))))]
    (map->GenAIClient {:project project
                       :location location
                       :model model
                       :system-instruction system-instruction
                       :response-schema resp-schema
                       :structured-responses? (boolean resp-schema)})))
