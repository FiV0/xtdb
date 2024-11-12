package xtdb.api

import kotlinx.serialization.Serializable
import xtdb.api.AuthnSettings.*

private val DEFAULT_IP4_RECORD = AuthnRecord("all", InetAddressRange.parse("127.0.0.1/32"), AuthnMethod.TRUST)
private val DEFAULT_IP6_RECORD = AuthnRecord("all", InetAddressRange.parse("0:0:0:0:0:0:0:1/128"), AuthnMethod.TRUST)

@Serializable
data class AuthnSettings(val authnRecords: List<AuthnRecord> = listOf(DEFAULT_IP4_RECORD, DEFAULT_IP6_RECORD)) {

    @Serializable
    enum class AuthnMethod {
        TRUST,
        PASSWORD,
    }

    @Serializable
    data class AuthnRecord(val user: String, val address: InetAddressRange, val method: AuthnMethod)

    fun addRecord(record: AuthnRecord) = AuthnSettings(authnRecords + record)
}
