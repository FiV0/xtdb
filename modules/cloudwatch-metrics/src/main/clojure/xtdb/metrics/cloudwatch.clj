(ns xtdb.metrics.cloudwatch
  (:require [xtdb.metrics :as metrics])
  (:import (io.micrometer.cloudwatch2 CloudWatchConfig CloudWatchMeterRegistry)
           (io.micrometer.core.instrument Clock MeterRegistry)
           (software.amazon.awssdk.services.cloudwatch CloudWatchAsyncClient)
           (xtdb.metrics CloudWatch CloudWatch$Factory)))

(defmethod metrics/->meter-registry-factory ::meter-registry [_ {:keys [namespace ^CloudWatchAsyncClient client]
                                                                 :or {namespace "xtdb.metrics"
                                                                      ^CloudWatchAsyncClient client (CloudWatchAsyncClient/create)}}]
  (CloudWatch/cloudWatch namespace client))

(defn create-meter-registry ^MeterRegistry [^CloudWatch$Factory factory]
  (CloudWatchMeterRegistry.
   (reify CloudWatchConfig
     (get [_ _s] nil)
     (namespace [_] (.getNamespace factory)))
   Clock/SYSTEM
   (.getClient factory)))
