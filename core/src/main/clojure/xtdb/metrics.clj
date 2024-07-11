(ns xtdb.metrics
  (:require [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.error :as err]
            [xtdb.node :as xtn]
            [xtdb.util :as util])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (io.micrometer.core.instrument Counter Gauge MeterRegistry Timer Timer$Sample)
           (io.micrometer.core.instrument.binder MeterBinder)
           (io.micrometer.core.instrument.binder.jvm ClassLoaderMetrics JvmGcMetrics JvmHeapPressureMetrics JvmMemoryMetrics JvmThreadMetrics)
           (io.micrometer.core.instrument.binder.system ProcessorMetrics)
           (io.micrometer.core.instrument.simple SimpleMeterRegistry)
           (io.micrometer.prometheus PrometheusMeterRegistry)
           (java.net InetSocketAddress)
           (java.util.function Supplier)
           (java.util.stream Stream)
           (xtdb.api MetricsConfigFactory LocalMetricsConfig LocalMetricsConfig$Factory Xtdb$Config)))

(defn meter-reg ^MeterRegistry
  ([] (meter-reg (SimpleMeterRegistry.)))
  ([meter-reg]
   (doseq [^MeterBinder metric [(ClassLoaderMetrics.) (JvmMemoryMetrics.) (JvmHeapPressureMetrics.)
                                (JvmGcMetrics.) (ProcessorMetrics.) (JvmThreadMetrics.)]]
     (.bindTo metric meter-reg))
   meter-reg))

(defn add-counter [reg name {:keys [description]}]
  (cond-> (Counter/builder name)
    description (.description description)
    :always (.register reg)))

(def percentiles [0.75 0.85 0.95 0.98 0.99 0.999])

(defn add-timer [reg name {:keys [^String description]}]
  (cond-> (.. (Timer/builder name)
              (publishPercentiles (double-array percentiles)))
    description (.description description)
    :always (.register reg)))

(defmacro wrap-query [q timer registry]
  (let [registry (vary-meta registry assoc :tag `MeterRegistry)]
    `(let [^Timer$Sample sample# (Timer/start ~registry)
           ^Stream stream# ~q]
       (.onClose stream# (fn [] (.stop sample# ~timer))))))

(defn add-gauge
  ([reg meter-name f] (add-gauge reg meter-name f {}))
  ([^MeterRegistry reg meter-name f opts]
   (-> (Gauge/builder
        meter-name
        (reify Supplier
          (get [_] (f))))
       (cond-> (:unit opts) (.baseUnit (str (:unit opts))))
       (.register reg))))

(defmulti ->metrics-config-factory
  #_{:clj-kondo/ignore [:unused-binding]}
  (fn [tag opts]
    (when-let [ns (and tag (namespace tag))]
      (doseq [k [(symbol ns)
                 (symbol (str ns "." (name tag)))]]

        (try
          (require k)
          (catch Throwable _))))

    tag)
  :default ::default)

(defmethod ->metrics-config-factory ::default [_ {:keys [port], :or {port 8080}}] (LocalMetricsConfig$Factory. port))
(defmethod ->metrics-config-factory :cloudwatch [_ opts] (->metrics-config-factory :xtdb.aws.cloudwatch/metrics-config opts))

(defmethod xtn/apply-config! :xtdb.metrics/metrics [^Xtdb$Config config, _ {:keys [type] :as opts}]
  (.setMetrics config (->metrics-config-factory type opts)))

(defmethod ig/prep-key :xtdb.metrics/metrics [_ ^MetricsConfigFactory opts]
  (if-not opts
    {:registry (SimpleMeterRegistry.)}
    (cond-> {:registry (.getMeterRegistry opts)}
      (instance? LocalMetricsConfig$Factory opts)
      (assoc :port (.getPort ^LocalMetricsConfig$Factory opts)))))

(defn- metrics-server [{:keys [^PrometheusMeterRegistry registry port]}]
  (try
    (let [port (if (util/port-free? port) port (util/free-port))
          http-server (HttpServer/create (InetSocketAddress. port) 0)]
      (.createContext http-server "/metrics"
                      (reify HttpHandler
                        (handle [_this exchange]
                          (let [response (.getBytes (.scrape registry))]
                            (.sendResponseHeaders exchange 200 (count response))
                            (with-open [os (.getResponseBody exchange)]
                              (.write os response))))))
      (.start http-server)
      (log/info "Metrics server started on port: " port)
      http-server)
    (catch java.io.IOException e
      (throw (err/runtime-err :metrics-server-error {} e)))))

(defmethod ig/init-key :xtdb.metrics/metrics [_ {:keys [registry] :as opts}]
  (cond-> {:registry (meter-reg registry)}
    (instance? PrometheusMeterRegistry registry) (assoc :server (metrics-server opts))))

(defmethod ig/halt-key! :xtdb.metrics/metrics [_ {:keys [^HttpServer server ^MeterRegistry registry]}]
  (when server
    (.stop server 0)
    (log/info "Metrics server stopped."))
  (.close registry))
