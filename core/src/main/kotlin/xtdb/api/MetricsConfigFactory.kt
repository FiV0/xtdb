package xtdb.api

import io.micrometer.core.instrument.MeterRegistry

interface MetricsConfigFactory {
    /**
     * Returns the [io.micrometer.core.instrument.MeterRegistry] for the node.
     */
   fun getMeterRegistry(): MeterRegistry
}