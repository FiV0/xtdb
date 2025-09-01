package xtdb.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.subclass
import xtdb.api.module.XtdbModule
import xtdb.util.requiringResolve

interface FlightSqlServer : XtdbModule {

    val port: Int

    @SerialName("!FlightSqlServer")
    @Serializable
    data class Factory(
        var host: String = "127.0.0.1",
        var port: Int = 0,
    ) : XtdbModule.Factory {
        override val moduleKey = "xtdb.flight-sql-server"

        fun host(host: String) = apply { this.host = host }
        fun port(port: Int) = apply { this.port = port }

        override fun openModule(xtdb: Xtdb) =
            requiringResolve("xtdb.flight-sql/open-server")(xtdb, this) as FlightSqlServer
    }

    class Registration : XtdbModule.Registration {
        override fun registerSerde(builder: PolymorphicModuleBuilder<XtdbModule.Factory>) {
            builder.subclass(Factory::class)
        }
    }
}

@JvmSynthetic
fun Xtdb.Config.flightSqlServer(configure: FlightSqlServer.Factory.() -> Unit = {}) {
    modules(FlightSqlServer.Factory().also(configure))
}
