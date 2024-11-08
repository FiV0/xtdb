@file:UseSerializers(InstantSerde::class, ZoneIdSerde::class)
package xtdb.api.tx

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xtdb.InstantSerde
import xtdb.ZoneIdSerde
import java.time.Instant
import java.time.ZoneId

@Serializable
data class TxOptions(
    val systemTime: Instant? = null,
    val defaultTz: ZoneId? = null,
    val user: String? = null
) {

    /**
     * @suppress
     */
    companion object {
        @JvmStatic
        fun txOpts() = Builder()
    }

    class Builder internal constructor(){
        private var systemTime: Instant? = null
        private var defaultTz: ZoneId? = null
        private var user: String? = null

        /**
         * Overrides the system time for the transaction.
         *
         * MUST NOT be earlier than any system time currently in the cluster - if so, the transaction will be cancelled.
         *
         * If not provided, defaults to the current wall-clock time at the transaction log.
         */
        fun systemTime(systemTime: Instant?) = apply { this.systemTime = systemTime }

        /**
         * The default time-zone that applies to any functions within the transaction without an explicitly specified time-zone.
         *
         * If not provided, defaults to UTC.
         */
        fun defaultTz(defaultTz: ZoneId?) = apply { this.defaultTz = defaultTz }

        fun user(user: String?) = apply { this.user = user}

        fun build() = TxOptions(systemTime, defaultTz, user)
    }
}

/**
 * Creates a tx-options builder.
 */
fun txOpts() = TxOptions.txOpts()
