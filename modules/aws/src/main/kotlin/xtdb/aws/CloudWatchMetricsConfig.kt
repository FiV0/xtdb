package xtdb.aws

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import xtdb.api.MetricsConfigFactory
import xtdb.api.module.XtdbModule
import xtdb.util.requiringResolve

object CloudWatchMetricsConfig {

    @JvmStatic
    fun cloudWatchMetricsConfig(namespace: String) = Factory(namespace)

    @JvmStatic
    fun cloudWatchMetricsConfig(namespace: String, client: CloudWatchAsyncClient) = Factory(namespace, client)

    @Serializable
    @SerialName("!CloudWatch")
    data class Factory(
        @Serializable val namespace: String = "xtdb.metrics",
        @Transient val client: CloudWatchAsyncClient = CloudWatchAsyncClient.create()
    ): MetricsConfigFactory {
        override fun getMeterRegistry(): MeterRegistry = requiringResolve("xtdb.aws.cloudwatch/create-meter-registry")(this) as MeterRegistry
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