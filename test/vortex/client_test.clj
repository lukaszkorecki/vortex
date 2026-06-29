(ns vortex.client-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest testing is]]
   [com.stuartsierra.component :as component]
   [malli.core :as m]
   [matcher-combinators.test :refer [match?]]
   [vortex.client :as client])
  (:import
   [com.google.genai.types Content GenerateContentResponse Part]))

;; --- test helpers -----------------------------------------------------------

(defn stub-response
  "Fabricate a real GenerateContentResponse carrying `text`, the way the SDK
  would hand it back, so output processing runs against the genuine type. A nil
  `text` produces a candidate-less response, whose `.text` is nil."
  ^GenerateContentResponse [text]
  (let [payload (if (nil? text)
                  {}
                  {:candidates [{:content {:role "model"
                                           :parts [{:text text}]}}]})]
    (GenerateContentResponse/fromJson (json/generate-string payload))))

(defn content->map
  "Read role/text back off a Content so we can assert on what was sent."
  [^Content c]
  {:role (-> (Content/.role c) .get)
   :text (-> (Content/.parts c) .get first (Part/.text) .get)})

;; --- create / schema compilation --------------------------------------------

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

;; --- generate-content* : one-shot request/response --------------------------
;;
;; `invoke-generate` is the SDK boundary; we redef it to capture the request and
;; stub the model's reply, leaving all input construction and output processing
;; in `generate-content*` under test.

(deftest generate-content*-test
  (testing "forwards model + input verbatim, parses JSON reply, strips nil keys"
    (let [sent (atom nil)]
      (with-redefs [client/invoke-generate (fn [_ {:keys [model input]}]
                                             (reset! sent {:model model :input input})
                                             (stub-response (json/generate-string {:name "John"
                                                                                   :age 42
                                                                                   :nickname nil})))]
        (let [result (client/generate-content* :fake-client
                                               {:model "gemini-2.0-flash"
                                                :config :fake-config
                                                :input "John is 42 years old."})]
          (is (= {:model "gemini-2.0-flash"
                  :input "John is 42 years old."}
                 @sent))
          ;; nil-valued keys are dropped, not passed through
          (is (= {:name "John" :age 42} result))))))

  (testing "a nil reply text yields nil rather than throwing"
    (with-redefs [client/invoke-generate (fn [& _] (stub-response nil))]
      (is (nil? (client/generate-content* :fake-client
                                          {:model "m" :config :c :input "x"}))))))

;; --- send-message* : multi-turn request/response ----------------------------

(deftest send-message*-first-turn-test
  (testing "with empty history: context is wrapped as the first user turn, the
            message follows once, and the reply is appended to history"
    (let [sent (atom nil)]
      (with-redefs [client/invoke-chat (fn [_ {:keys [model config contents]}]
                                         (reset! sent {:model model
                                                       :config config
                                                       :contents (mapv content->map contents)})
                                         (stub-response "The size is 20cm by 20cm."))]
        (let [result (client/send-message* :fake-client
                                           {:model "gemini-2.5-flash"
                                            :config :the-gen-config
                                            :context "## Product info: size 20cm by 20cm"
                                            :history []
                                            :message "what is the size?"})]
          ;; exactly two user turns are sent: context, then the message — no dupe
          (is (= [{:role "user"
                   :text (str "<PRIVATE CONTEXT>\n# Context for this conversation\n\n"
                              "## Product info: size 20cm by 20cm\n\n</PRIVATE CONTEXT>")}
                  {:role "user" :text "what is the size?"}]
                 (:contents @sent)))
          (is (= "gemini-2.5-flash" (:model @sent)))
          ;; the component's gen-config is forwarded untouched
          (is (= :the-gen-config (:config @sent)))
          ;; the returned history is what was sent + the model reply
          (is (= {:reply "The size is 20cm by 20cm."
                  :raw-reply "The size is 20cm by 20cm."
                  :finish-reason "FINISH_REASON_UNSPECIFIED"
                  :function-calls []
                  :history [{:role "user"
                             :text (str "<PRIVATE CONTEXT>\n# Context for this conversation\n\n"
                                        "## Product info: size 20cm by 20cm\n\n</PRIVATE CONTEXT>")}
                            {:role "user" :text "what is the size?"}
                            {:role "model" :text "The size is 20cm by 20cm."}]}
                 result)))))))

(deftest send-message*-continuation-test
  (testing "with existing history: no context is injected, prior turns are
            preserved, and only the new message + reply are appended"
    (let [history [{:role "user"
                    :text "<PRIVATE CONTEXT>\n# Context for this conversation\n\nctx\n\n</PRIVATE CONTEXT>"}
                   {:role "user" :text "what is the size?"}
                   {:role "model" :text "The size is 20cm by 20cm."}]
          sent (atom nil)]
      (with-redefs [client/invoke-chat (fn [_ {:keys [contents]}]
                                         (reset! sent (mapv content->map contents))
                                         (stub-response "The color is black."))]
        (let [result (client/send-message* :fake-client
                                           {:model "gemini-2.5-flash"
                                            :config :cfg
                                            ;; context is ignored once history exists
                                            :context "## SHOULD NOT APPEAR"
                                            :history history
                                            :message "and the color?"})]
          ;; the prior history is sent verbatim with the new message appended once
          (is (= (conj history {:role "user" :text "and the color?"})
                 @sent))
          ;; the stale context never leaks into the sent contents
          (is (not-any? #(re-find #"SHOULD NOT APPEAR" (:text %)) @sent))
          (is (= (into history [{:role "user" :text "and the color?"}
                                {:role "model" :text "The color is black."}])
                 (:history result))))))))

(deftest no-system-prompt-no-context-test
  (let [sent (atom nil)]
    (with-redefs [client/invoke-chat (fn [_ {:keys [model config contents]}]
                                       (reset! sent {:model model
                                                     :config config
                                                     :contents (mapv content->map contents)})
                                       (stub-response "hello"))]
      (let [_result (client/send-message* :fake-client
                                          {:model "gemini-2.5-flash"
                                           :config :the-gen-config
                                           :history []
                                           :message "hello"})]

        (is (= {:config :the-gen-config :contents [{:role "user" :text "hello"}] :model "gemini-2.5-flash"}
               @sent))))))

;; --- protocol dispatch ------------------------------------------------------
;;
;; The record's IGenAI methods are thin wiring: they pull `gen-config`/`model`/
;; `client` off the record and hand them, plus the call args, to the (already
;; tested) `*` collaborators. We stub those collaborators to assert the dispatch
;; forwards the right record data + args, without re-exercising their logic.

(deftest protocol-dispatch-test
  (let [client (client/map->GenAIClient {:client :the-client
                                         :gen-config :the-gen-config
                                         :model "gemini-2.5-flash"})
        captured (atom nil)]
    (testing "generate-content forwards the record's client, gen-config + model and the input"
      (with-redefs [client/generate-content* (fn [c opts]
                                               (reset! captured {:client c :opts opts})
                                               :generated)]
        (is (= :generated (client/generate-content client {:input "John is 42 years old."})))
        (is (= {:client :the-client
                :opts {:config :the-gen-config
                       :model "gemini-2.5-flash"
                       :input "John is 42 years old."}}
               @captured))))

    (testing "send-message forwards the record's client, gen-config + model and the message/history/context"
      (with-redefs [client/send-message* (fn [c opts]
                                           (reset! captured {:client c :opts opts})
                                           :sent)]
        (is (= :sent (client/send-message client {:message "and the color?"
                                                  :history [:prior]
                                                  :context "ctx"})))
        (is (= {:client :the-client
                :opts {:config :the-gen-config
                       :structured-responses? true
                       :model "gemini-2.5-flash"
                       :context "ctx"
                       :message "and the color?"
                       :history [:prior]}}
               @captured))))))
