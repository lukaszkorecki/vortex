# vortex

Thin Clojure client for Google GenAI / Vertex AI, with Malli-driven
structured output.

## Features

- Malli schema → Gemini/Vertex-flavored JSON Schema
- `com.stuartsierra/component` lifecycle wrapping `com.google.genai.Client` for easy integration
- Supports;
  - one shot structured output, via `vortex.client/generate-content`
  - multi-turn chat with `vortex.client/send-message`

## Usage

```clojure
(require '[com.stuartsierra.component :as component]
         '[vortex.client :as vortex])

(def client
  (component/start
   (vortex/create {:project "my-gcp-project"
                   :location "us-central1"
                   :model "gemini-3.5-flash"
                   :system-instruction "Extract structured data from the input."
                   :response-schema [:map
                                     [:name :string]
                                     [:age :int]]})))

(vortex/generate-content client "John is 42 years old.")
;; => {:name "John" :age 42}

(component/stop client)
```

`:response-schema` accepts either a raw Malli schema vector or a
pre-compiled `malli.core/schema` value.

## Chat

`send-message` drives a multi-turn conversation. Unlike `generate-content`,
you create the client **without** a `:response-schema` — chat replies are
plain text, not structured JSON.

Each call takes a map of:

- `:message` - the user's message for this turn (required)
- `:history` - the conversation so far, as returned by the previous call
  (pass `[]` on the first turn)
- `:context` - private context injected at the start of a fresh
  conversation, only used when `:history` is empty

The call returns `{:reply <string> :history <vec>}`. Feed the returned
`:history` back into the next `send-message` to continue the conversation.

```clojure
(def chat
  (component/start
    (vortex/create {:project "my-gcp-project"
       :location "us-central1"
       :model "gemini-3.5-flash"
       :system-instruction "Help the user answer questions about the product"})))

(vortex/send-message chat {:context "## Product info: size 20cm by 20cm, color black"
                           :history []
                           :message "what is the size of this product?"})
;; => {:reply "The size of this product is 20cm by 20cm."
;;     :history
;;     [{:role "user"
;;       :text "<PRIVATE CONTEXT>\n# Context for this conversation\n\n## Product info: size 20cm by 20cm, color black\n\n</PRIVATE CONTEXT>"}
;;      {:role "user" :text "what is the size of this product?"}
;;      {:role "model" :text "The size of this product is 20cm by 20cm."}]}

;; continue the conversation by passing the returned :history back in
(vortex/send-message chat {:history (:history *1)
                           :message "and what is the color?"})
;; => {:reply "The color of this product is black."
;;     :history
;;     [{:role "user"
;;       :text "<PRIVATE CONTEXT>\n# Context for this conversation\n\n## Product info: size 20cm by 20cm, color black\n\n</PRIVATE CONTEXT>"}
;;      {:role "user" :text "what is the size of this product?"}
;;      {:role "model" :text "The size of this product is 20cm by 20cm."}
;;      {:role "user" :text "and what is the color?"}
;;      {:role "model" :text "The color of this product is black."}]}
```

The `:context` is wrapped in `<PRIVATE CONTEXT>` markers and inserted as the
first `user` turn of a new conversation; subsequent turns reuse the history
verbatim, so the context is sent once rather than re-prepended to every
message.

### Usage without Component

use `vortex.client` namespace and it's functions to:

- construct the client via `->client`
- content generation configuration (`->config`) to create config

and use `generate-content*` or `send-message*` directly.

## Schema transform

`vortex.schema/->json-schema-for-inference` is exposed on its own for
callers that want to build `GenerateContentConfig` directly:

```clojure
(require '[vortex.schema :as schema])

(schema/->json-schema-for-inference
  [:map [:name :string] [:age :int]])
;; => {:type "OBJECT"
;;     :properties {:name {:type "STRING"}
;;                  :age  {:type "INTEGER"}}
;;     :required [...]}
```

Pass `:all-required? true` to strip `:optional` markers before the
transform.

# Roadmap

- [ ] tool calls
- [ ] grounding https://ai.google.dev/gemini-api/docs/google-search
- [ ] drop GenAI SDK and use REST API directly?
