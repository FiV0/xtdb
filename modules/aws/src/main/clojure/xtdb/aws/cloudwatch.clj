(ns xtdb.aws.cloudwatch
  (:require [xtdb.metrics :as metrics])
  (:import (io.micrometer.cloudwatch2 CloudWatchConfig CloudWatchMeterRegistry)
           (io.micrometer.core.instrument Clock MeterRegistry)
           (software.amazon.awssdk.services.cloudwatch CloudWatchAsyncClient)
           (xtdb.aws CloudWatchMetricsConfig CloudWatchMetricsConfig$Factory)))

(defmethod metrics/->metrics-config-factory ::metrics-config [_ {:keys [namespace ^CloudWatchAsyncClient client]
                                                                 :or {namespace "xtdb.metrics"
                                                                      ^CloudWatchAsyncClient client (CloudWatchAsyncClient/create)}}]
  (CloudWatchMetricsConfig/cloudWatchMetricsConfig namespace client))

(defn create-meter-registry ^MeterRegistry [^CloudWatchMetricsConfig$Factory factory]
  (CloudWatchMeterRegistry.
   (reify CloudWatchConfig
     (get [_ _s] nil)
     (namespace [_] (.getNamespace factory)))
   Clock/SYSTEM
   (.getClient factory)))
