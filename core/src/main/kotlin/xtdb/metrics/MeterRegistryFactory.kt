package xtdb.metrics

import io.micrometer.core.instrument.MeterRegistry

interface MeterRegistryFactory {
    fun create(): MeterRegistry
}