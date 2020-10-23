(ns voila-api.core
  (:require muuntaja.core
            ring.middleware.params
            reitit.coercion
            reitit.coercion.spec
            reitit.ring
            reitit.ring.coercion
            reitit.ring.middleware.muuntaja
            reitit.ring.middleware.parameters
            reitit.swagger
            reitit.swagger-ui
            [ring.adapter.jetty :as ring-jetty]
            ring.middleware.cors
            [voila-api.events :as events]
            [voila-api.users :as users]
            [voila-api.event-processor :as event-processor]
            [voila-api.learner-representation :as learner-representation])
  (:gen-class))

(def healthcheck-resource
  {:name :resources/healthcheck
   :get  {:handler (constantly
                     {:status 200
                      :body   {:status "OK"}})}})

(def recommendations-resource
  {:name    :resources/recommentations
   :get     {:summary    "Get a user's content recommendations"
             :parameters {:path {:user-id int?}}
             :handler    (fn [request]
                           {:status 200
                            :body   {:items (repeatedly 10 #(voila-api.content/generate-content))}})}
   :options {:no-doc  true
             :handler (constantly {:status 200})}})

(defn learner-representation-resource
  [{storage :storage}]
  {:name       :resources/learner-representation
   :parameters {:path {:user-id string?}}
   :get        {:summary "Get a user's learner representation"
                :handler (fn [{:keys [parameters]}]
                           (let [user-id (get-in parameters [:path :user-id])]
                             {:status 200
                              :body   (learner-representation/retrieve storage [user-id])}))}
   :options    {:no-doc  true
                :handler (constantly {:status 200})}})

(defn events-resource
  [{event-processor :event-processor}]
  {:name    :resources/events
   :get     {:summary    "Get generated events"
             :parameters {:query {:user-id    string?
                                  :event-type string?}}
             :handler    (fn [{{{:keys [user-id event-type]} :query} :parameters}]
                           (let [event (events/generate-event-from-data
                                         {:actor-global-id user-id
                                          :actor-local-id  user-id
                                          :verb            event-type})]
                             (if event
                               {:status 200
                                :body   event}
                               {:status 500})))}
   :post    {:summary    "Send an event as on behalf of a user"
             :parameters {:body {:event-data map?}}
             :response   {200 {:body {:event-response map?}}}
             :handler    (fn [{{{:keys [event-data]} :body} :parameters}]
                           (let [event-response
                                  (event-processor/process-data event-processor event-data)]
                              (if event-response
                                {:status 200
                                 :body   {:event-response event-response}}
                                {:status 500})))}
   :options {:no-doc  true
             :handler (constantly {:status 200})}})

(defn root-handler
  [components]
  (reitit.ring/ring-handler
    (reitit.ring/router
      [["/healthcheck" healthcheck-resource]
       ["/api/v1"
        ["/users/:user-id/recommendations" recommendations-resource]
        ["/users/:user-id/representation" (learner-representation-resource components)]]
       ["/dev"
        ["/events" (events-resource components)]]
       ["" {:no-doc true}
        ["/swagger.json" {:get (reitit.swagger/create-swagger-handler)}]
        ["/api-docs/*" {:get (reitit.swagger-ui/create-swagger-ui-handler)}]
        ["/internal/app/*" {:get (reitit.ring/create-file-handler)}]]]
      {:data {:coercion   reitit.coercion.spec/coercion
              :muuntaja   muuntaja.core/instance
              :middleware [reitit.ring.middleware.parameters/parameters-middleware
                           reitit.ring.middleware.muuntaja/format-middleware
                           reitit.ring.coercion/coerce-exceptions-middleware
                           reitit.ring.coercion/coerce-request-middleware
                           reitit.ring.coercion/coerce-response-middleware
                           [ring.middleware.cors/wrap-cors
                            :access-control-allow-origin [#".*"]
                            :access-control-allow-methods [:get :put :post :delete :options]]]}})
    (reitit.ring/routes
      (reitit.ring/redirect-trailing-slash-handler)
      (reitit.ring/create-default-handler))))

(defonce server-instance (atom nil))

(defn stop-server
  []
  (when @server-instance
    (.stop @server-instance)))

(defn create-components
  []
  {:event-processor (event-processor/->DummyEventProcessor learner-representation/atomic-store)
   :storage learner-representation/atomic-store})

(defn start-server
  [port]
  (stop-server)
  (reset! server-instance
          (ring-jetty/run-jetty (root-handler (create-components))
                                {:port  port
                                 :join? false})))

(defn -main
  [& args]
  (let [port (Integer/parseInt (get (System/getenv) "PORT" "9000"))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop-server))
    (start-server port)
    (println "Running")))

(comment
  ;; start the server from within an nrepl session
  (prn @server-instance)
  (stop-server)
  (start-server 9000))
