(ns xtdb.server
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [muuntaja.core :as m]
            [muuntaja.format.core :as mf]
            [reitit.coercion :as r.coercion]
            [reitit.coercion.spec :as rc.spec]
            [reitit.core :as r]
            [reitit.http :as http]
            [reitit.http.coercion :as rh.coercion]
            [reitit.http.interceptors.exception :as ri.exception]
            [reitit.http.interceptors.muuntaja :as ri.muuntaja]
            [reitit.http.interceptors.parameters :as ri.parameters]
            [reitit.interceptor.sieppari :as r.sieppari]
            [reitit.ring :as r.ring]
            [reitit.swagger :as r.swagger]
            [ring.adapter.jetty9 :as j]
            [ring.util.response :as ring-response]
            [spec-tools.core :as st]
            [xtdb.api :as xt]
            [xtdb.error :as err]
            [xtdb.node :as xtn]
            [xtdb.pgwire :as pgwire]
            [xtdb.protocols :as xtp]
            [xtdb.serde :as serde]
            [xtdb.time :as time]
            [xtdb.util :as util])
  (:import (java.io InputStream OutputStream)
           (java.time Duration ZoneId)
           (java.util List Map)
           [java.util.function Consumer]
           [java.util.stream Stream]
           javax.naming.AuthenticationException
           org.eclipse.jetty.server.Server
           (xtdb JsonSerde)
           (xtdb.api HttpServer$Factory TransactionKey Xtdb$Config)
           xtdb.api.module.XtdbModule
           (xtdb.api.query IKeyFn Query)
           (xtdb.http AuthOptions Basis QueryOptions QueryRequest TxOptions TxRequest )))

(def json-format
  (mf/map->Format
   {:name "application/json"
    :decoder [(fn decoder [_opts]
                (reify
                  mf/Decode
                  (decode [_ data _charset] ; TODO charset
                    (if (string? data)
                      (JsonSerde/decode ^String data)
                      (JsonSerde/decode ^InputStream data)))))]

    :encoder [(fn encoder [_opts]
                (reify
                  mf/EncodeToBytes
                  (encode-to-bytes [_ data charset]
                    (.getBytes ^String (JsonSerde/encode data) ^String charset))
                  mf/EncodeToOutputStream
                  (encode-to-output-stream [_ data _charset] ; TODO charset
                    (fn [^OutputStream output-stream]
                      (JsonSerde/encode data output-stream)))))]}))

(def ^:private muuntaja-opts
  (-> m/default-options
      (m/select-formats #{"application/transit+json"})
      (assoc-in [:formats "application/json"] json-format)
      (assoc :default-format "application/json")
      (assoc-in [:formats "application/transit+json" :decoder-opts :handlers] serde/transit-read-handlers)
      (assoc-in [:formats "application/transit+json" :encoder-opts :handlers] serde/transit-write-handlers)
      (assoc-in [:http :encode-response-body?] (constantly true))))

(defmulti ^:private route-handler :name, :default ::default)

(s/def ::tx-ops seqable?)

(s/def ::key-fn (s/nilable #(instance? IKeyFn %)))

(s/def ::default-tz #(instance? ZoneId %))
(s/def ::explain? boolean?)

(s/def ::system-time ::time/datetime-value)

(s/def ::user string?)
(s/def ::password string?)
(s/def ::auth-opts (s/keys :req-un [::user ]
                           :opt-un [::password]))

(s/def :xtdb.server.status/opts (s/nilable (s/keys :opt-un [::auth-opts])))
(s/def :xtdb.server.tx/opts (s/nilable (s/keys :opt-un [::system-time ::default-tz ::key-fn ::explain? ::auth-opts])))

(defn- json-status-encoder []
  (reify
    mf/EncodeToBytes
    (encode-to-bytes [_ data charset]
      (if-not (ex-data data)
        (.getBytes (JsonSerde/encodeStatus (update-keys data name)) ^String charset)
        (.getBytes (JsonSerde/encode data) ^String charset)))
    mf/EncodeToOutputStream))

(defmethod route-handler :status [_]
  {:muuntaja (m/create (-> muuntaja-opts
                           (assoc-in [:formats "application/json" :encoder] (json-status-encoder))))

   :post {:handler (fn [{:keys [node] :as _req}]
                     {:status 200, :body (xtp/status node)})

          :parameters {:body (s/keys :opt-un [:xtdb.server.status/opts])}}})

(def ^:private json-tx-encoder
  (reify
    mf/EncodeToBytes
    (encode-to-bytes [_ data charset]
      (if-not (ex-data data)
        (.getBytes (JsonSerde/encode data TransactionKey) ^String charset)
        (.getBytes (JsonSerde/encode data) ^String charset)))
    mf/EncodeToOutputStream))

(defn <-AuthOptions [^AuthOptions opts]
  (->> {:user (.getUser opts)
        :password (.getPassword opts)}
       (into {} (remove (comp nil? val)))))

(defn- <-TxOptions [^TxOptions opts]
  (->> (cond-> {:system-time (.getSystemTime opts)
                :default-tz (.getDefaultTz opts)}
         (.getAuthOpts opts) (assoc :auth-opts (<-AuthOptions (.getAuthOpts opts))))
       (into {} (remove (comp nil? val)))))

(def ^:private json-tx-decoder
  (reify
    mf/Decode
    (decode [_ data _]
      (with-open [^InputStream data data]
        (let [^TxRequest tx (JsonSerde/decode data TxRequest)]
          {:tx-ops (.getTxOps tx)
           :opts (some-> (.getOpts tx) <-TxOptions)})))))

(defmethod route-handler :tx [_]
  {:muuntaja (m/create (-> muuntaja-opts
                           (assoc-in [:formats "application/json" :encoder] json-tx-encoder)
                           (assoc-in [:formats "application/json" :decoder] json-tx-decoder)))

   :post {:handler (fn [{:keys [node] :as req}]
                     (let [{:keys [tx-ops opts await-tx?]} (get-in req [:parameters :body])]
                       {:status 200
                        :body (if await-tx?
                                (xtp/execute-tx node tx-ops opts)
                                (xtp/submit-tx node tx-ops opts))}))

          :parameters {:body (s/keys :req-un [::tx-ops]
                                     :opt-un [:xtdb.server.tx/opts ::await-tx?])}}})

(defn- throwable->ex-info [^Throwable t]
  (ex-info (.getMessage t) {::err/error-type :unknown-runtime-error
                            :class (.getName (.getClass t))
                            :stringified (.toString t)}))

(defn- ->tj-resultset-encoder [opts]
  (reify
    mf/EncodeToBytes
    ;; we're required to be a sub-type of ETB but don't need to implement its fn.

    mf/EncodeToOutputStream
    (encode-to-output-stream [_ res _]
      (fn [^OutputStream out]
        (if-not (ex-data res)
          (with-open [^Stream res res
                      out out]
            (let [writer (transit/writer out :json opts)]
              (try
                (.forEach res
                          (reify Consumer
                            (accept [_ el]
                              (transit/write writer el))))
                (catch xtdb.RuntimeException e
                  (transit/write writer e))
                (catch Throwable t
                  (transit/write writer (throwable->ex-info t))))))
          (with-open [out out]
            (let [writer (transit/writer out :json opts)]
              (transit/write writer res))))))))

(def ^:private ascii-newline (int \newline))

(defn- ->jsonl-resultset-encoder [_opts]
  (reify
    mf/EncodeToBytes

    mf/EncodeToOutputStream
    (encode-to-output-stream [_ res _]
      (fn [^OutputStream out]
        (if-not (ex-data res)
          (with-open [^Stream res res]
            (try
              (.forEach res
                        (reify Consumer
                          (accept [_ el]
                            (JsonSerde/encode el out)
                            (.write out ^byte ascii-newline))))
              (catch Throwable t
                (JsonSerde/encode t out)
                (.write out ^byte ascii-newline))
              (finally
                (util/close res)
                (util/close out))))

          (try
            (JsonSerde/encode res out)
            (finally
              (util/close out))))))))

(defn- ->json-resultset-encoder [_opts]
  (reify
    mf/EncodeToBytes

    mf/EncodeToOutputStream
    (encode-to-output-stream [_ res _]
      (fn [^OutputStream out]
        (if-not (ex-data res)
          (with-open [^Stream res res]
            (try
              (JsonSerde/encode (.toList res) out)

              (catch Throwable t
                (JsonSerde/encode t out)
                (.write out ^byte ascii-newline))
              (finally
                (util/close res)
                (util/close out))))

          (try
            (JsonSerde/encode res out)
            (finally
              (util/close out))))))))

(s/def ::current-time inst?)
(s/def ::at-tx (s/nilable #(instance? TransactionKey %)))
(s/def ::after-tx (s/nilable #(instance? TransactionKey %)))
(s/def ::basis (s/nilable (s/or :class #(instance? Basis %)
                                :map (s/keys :opt-un [::current-time ::at-tx]))))

(s/def ::tx-timeout
  (st/spec (s/nilable #(instance? Duration %))
           {:decode/string (fn [_ s] (some-> s Duration/parse))}))

(s/def ::query (some-fn string? seq? #(instance? Query %)))

(s/def ::args (s/nilable (some-fn #(instance? Map %)
                                  #(instance? List %))))

(s/def ::query-body
  (s/keys :req-un [::query],
          :opt-un [::after-tx ::basis ::tx-timeout ::args ::default-tz ::key-fn ::explain? ::auth-opts]))

(defn- <-QueryOpts [^QueryOptions opts]
  (->> (cond-> {:args (.getArgs opts)

                :after-tx (.getAfterTx opts)
                :basis (->> (if-let [basis (.getBasis opts)]
                              (if (instance? Basis basis)
                                {:current-time (.getCurrentTime basis)
                                 :at-tx (.getAtTx basis)}
                                basis)
                              nil)
                            (into {} (remove (comp nil? val))))

                :tx-timeout (.getTxTimeout opts)

                :default-tz (.getDefaultTz opts)
                :explain? (.getExplain opts)
                :key-fn (.getKeyFn opts)}
         (.getAuthOpts opts) (assoc :auth-opts (<-AuthOptions (.getAuthOpts opts))))
       (into {} (remove (comp nil? val)))))

(defn json-query-decoder []
  (reify
    mf/Decode
    (decode [_ data _]
      (with-open [^InputStream data data]
        (let [^QueryRequest query-request (JsonSerde/decode data QueryRequest)]
          (-> (<-QueryOpts (.queryOpts query-request))
              (assoc :query (.sql query-request))))))))

(defmethod route-handler :query [_]
  {:muuntaja (m/create (-> muuntaja-opts
                           (assoc :return :output-stream)

                           (assoc-in [:formats "application/transit+json" :encoder]
                                     [->tj-resultset-encoder {:handlers serde/transit-write-handlers}])

                           (assoc-in [:formats "application/json" :encoder]
                                     [->json-resultset-encoder {}])

                           (assoc-in [:formats "application/jsonl" :encoder]
                                     [->jsonl-resultset-encoder {}])

                           (assoc-in [:formats "application/json" :encoder]
                                     [->json-resultset-encoder {}])

                           (assoc-in [:formats "application/json" :decoder] (json-query-decoder))))

   :post {:handler (fn [{:keys [node parameters]}]
                     (let [{{:keys [query] :as query-opts} :body} parameters]
                       {:status 200
                        :body (cond
                                (string? query) (xtp/open-sql-query node query
                                                                    (into {:key-fn #xt/key-fn :snake-case-string}
                                                                          (dissoc query-opts :query)))

                                (seq? query) (xtp/open-xtql-query node query
                                                                  (into {:key-fn #xt/key-fn :snake-case-string}
                                                                        (dissoc query-opts :query)))

                                :else (throw (err/illegal-arg :unknown-query-type {:query query, :type (type query)})))}))

          :parameters {:body ::query-body}}})

(defmethod route-handler :openapi [_]
  {:get {:handler (fn [_req]
                    (-> (ring-response/resource-response "openapi.yaml")
                        (assoc "Access-Control-Allow-Origin" "*")))
         :muuntaja (m/create m/default-options)}})

(defn- handle-ex-info [ex _req]
  {:status 400, :body ex})

(defn- handle-request-coercion-error [ex _req]
  {:status 400
   :body (err/illegal-arg :malformed-request
                          (merge (r.coercion/encode-error (ex-data ex))
                                 {::err/message "malformed request"}))})

(defn- unroll-xt-iae [ex]
  (if (instance? xtdb.IllegalArgumentException ex)
    ex
    (when-let [ex (ex-cause ex)]
      (recur ex))))

(defn- handle-muuntaja-decode-error [ex _req]
  (if-let [xt-iae (unroll-xt-iae ex)]
    {:status 400
     :body xt-iae}
    {:status 400
     :body (err/illegal-arg :malformed-request
                            (merge {::err/message (str "Malformed " (-> ex ex-data :format pr-str) " request.")}
                                   #_(ex-data ex)))}))



(defn- auth-ex-handler [^Exception e _]
  {:status 401 :body (ex-info (.getMessage e) {::err/error-type :authentication-error
                                               :class (.getName (.getClass e))
                                               :stringified (.toString e)})})

(defn- default-handler [^Exception e _]
  {:status 500 :body (throwable->ex-info e)})

(def authenticate-interceptor
  {:name ::authenticate
   :enter (fn [{:keys [request] :as ctx}]
            (let [{:keys [node authn-records remote-addr]} request
                  {:keys [user password] :or {user "all"}}
                  ;; query body is already considered options
                  (or (get-in request [:parameters :body :opts :auth-opts])
                      (get-in request [:parameters :body :auth-opts]))]
              (if-let [{:keys [method]} (pgwire/most-specific-record authn-records remote-addr user)]
                (when-not (= :trust method)
                  (if-let [{record-password :password} (pgwire/user+password node user)]
                    (when-not (= (util/md5 password) record-password)
                      (throw (AuthenticationException. "Invalid password")))

                    ;; there was a authn record, but no user in the database
                    (throw (AuthenticationException. (str "password authentication failed for user: " user)))))

                (throw (AuthenticationException. (str "no authentication record found for user: " user))))

              (-> ctx
                  (update-in [:request :parameters :body] dissoc :auth-opts)
                  (update-in [:request :parameters :body :opts] dissoc :auth-opts))))})

(def router
  (http/router xtp/http-routes
               {:expand (fn [{route-name :name, :as route} opts]
                          (r/expand (cond-> route
                                      route-name (merge (route-handler route)))
                                    opts))

                :data {:muuntaja (m/create muuntaja-opts)
                       :coercion rc.spec/coercion
                       :interceptors [r.swagger/swagger-feature
                                      [ri.parameters/parameters-interceptor]
                                      [ri.muuntaja/format-negotiate-interceptor]

                                      [ri.muuntaja/format-response-interceptor]

                                      [ri.exception/exception-interceptor
                                       (merge ri.exception/default-handlers
                                              {::ri.exception/default default-handler
                                               AuthenticationException auth-ex-handler
                                               xtdb.IllegalArgumentException handle-ex-info
                                               xtdb.RuntimeException handle-ex-info
                                               ::r.coercion/request-coercion handle-request-coercion-error
                                               :muuntaja/decode handle-muuntaja-decode-error
                                               ::ri.exception/wrap (fn [handler e req]
                                                                     (log/debug e (format "response error (%s): '%s'" (class e) (ex-message e)))
                                                                     (let [response-format (:raw-format (:muuntaja/response req))]
                                                                       (cond-> (handler e req)
                                                                         (#{"application/jsonl"} response-format)
                                                                         (assoc :muuntaja/content-type "application/json"))))})]

                                      [ri.muuntaja/format-request-interceptor]
                                      [rh.coercion/coerce-request-interceptor]
                                      authenticate-interceptor]}}))

(defn- with-opts [opts]
  {:enter (fn [ctx]
            (update ctx :request merge opts))})


(defn handler [node opts]
  (http/ring-handler router
                     (r.ring/create-default-handler)
                     {:executor r.sieppari/executor
                      :interceptors [[with-opts (merge {:node node} opts)]
                                     #_authenticate-interceptor]}))


#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn open-server [node ^HttpServer$Factory module]
  (let [port (.getPort module)
        authn-records (pgwire/<-authn-settings (.getAuthnSettings module))
        ^Server server (j/run-jetty (handler node {:authn-records authn-records})
                                    (merge {:port port, :h2c? true, :h2? true}
                                           #_jetty-opts
                                           {:async? true, :join? false}))]
    (log/info "HTTP server started on port:" port)
    (reify XtdbModule
      (close [_]
        (.stop server)
        (log/info "HTTP server stopped.")))))

(defmethod xtn/apply-config! :xtdb/server [^Xtdb$Config config, _ {:keys [port authn-records]}]
  (.module config (cond-> (HttpServer$Factory.)
                    (some? port) (.port port)
                    (seq authn-records) (.authn (pgwire/->authn-settings authn-records)))))
