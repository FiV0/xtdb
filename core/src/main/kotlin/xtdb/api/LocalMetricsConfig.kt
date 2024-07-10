package xtdb.api

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xtdb.api.module.XtdbModule

object LocalMetricsConfig {

    @JvmStatic
    fun localMetricsConfig(port : Long) = Factory(port)

    @Serializable
    @SerialName("!Local")
    data class Factory(
        @Serializable val port: Long = 8080
    ): MetricsConfigFactory {
        override fun getMeterRegistry(): MeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    }

    /**
     * @suppress
     */
    class Registration : XtdbModule.Registration {
        override fun register(registry: XtdbModule.Registry) {
            registry.registerMetricsConfigFactory(Factory::class)
        }
    }
}