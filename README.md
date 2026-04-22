# vortex

Thin Clojure client for Google GenAI / Vertex AI, with Malli-driven
structured output.

## Features

- Malli schema → Gemini/Vertex-flavored JSON Schema (uppercased `:type`)
- `com.stuartsierra/component` lifecycle wrapping `com.google.genai.Client`
- `IGenAI` protocol with a single `generate-content` method

## Usage

```clojure
(require '[com.stuartsierra.component :as component]
         '[vortex.client :as vortex])

(def client
  (component/start
    (vortex/create
      {:project "my-gcp-project"
       :location "us-central1"
       :model "gemini-2.0-flash"
       :system-instruction "Extract structured data from the input."
       :response-schema [:map
                         [:name :string]
                         [:age :int]]})))

(vortex/generate-content client "John is 42 years old.")
;; => {:name "John" :age 42}

(component/stop client)
```

`:response-schema` accepts either a raw Malli schema vector or an
already-compiled `malli.core/schema` value.

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

## Tasks

Tasks are managed with [mise](https://mise.jdx.dev/):

```
mise run test                           # run the test suite
mise run jar                            # build a jar
mise run install                        # install locally
mise run release                        # clean + jar + publish to Clojars
SNAPSHOT=foo mise run release           # snapshot release
```
