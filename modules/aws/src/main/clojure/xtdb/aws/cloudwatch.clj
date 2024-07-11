(ns xtdb.aws.cloudwatch
  (:require [xtdb.node :as xtn])
  (:import (software.amazon.awssdk.services.cloudwatch CloudWatchAsyncClient)
           (xtdb.aws CloudWatchMetrics$Factory)))

(defmethod xtn/apply-config! ::metrics [config _ {:keys [namespace client]
                                                  :or {namespace "xtdb.metrics"
                                                       client (CloudWatchAsyncClient/create)}}]
  (.setMetrics config (CloudWatchMetrics$Factory. namespace client)))
