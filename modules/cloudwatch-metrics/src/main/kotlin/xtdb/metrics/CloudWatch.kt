@file:UseSerializers(PathWithEnvVarSerde::class, StringWithEnvVarSerde::class)

package xtdb.metrics

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import xtdb.api.PathWithEnvVarSerde
import xtdb.api.StringWithEnvVarSerde
import  software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import xtdb.api.module.XtdbModule
import xtdb.util.requiringResolve

object CloudWatch {
    @JvmStatic
    fun cloudWatch(namespace: String) = Factory(namespace)

    @JvmStatic
    fun cloudWatch(namespace: String, client: CloudWatchAsyncClient) = Factory(namespace, client)

    @Serializable
    @SerialName("!CloudWatch")
    data class Factory(
        @Serializable(StringWithEnvVarSerde::class) val namespace: String,
        @Transient var client: CloudWatchAsyncClient = CloudWatchAsyncClient.create(),
    ) : MeterRegistryFactory {

        override fun create() = requiringResolve("xtdb.metrics.cloudwatch/create-meter-registry")(this) as MeterRegistry
    }

    /**
     * @suppress
     */
    class Registration : XtdbModule.Registration {
        override fun register(registry: XtdbModule.Registry) {
            registry.registerMeterRegistry(Factory::class)
        }
    }
}