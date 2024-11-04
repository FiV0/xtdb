package xtdb.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xtdb.api.AuthnSettings.*

private val DEFAULT_IP4_RECORD = AuthnRecord("all", "127.0.0.1", AuthnMethod.TRUST)
private val DEFAULT_IP6_RECORD = AuthnRecord("all", "0:0:0:0:0:0:0:1", AuthnMethod.TRUST)

@Serializable
data class AuthnSettings(val authnRecords: List<AuthnRecord> = listOf(DEFAULT_IP4_RECORD, DEFAULT_IP6_RECORD)) {

    @Serializable
    enum class AuthnMethod {
        TRUST,
        PASSWORD,
    }

    @Serializable
    data class AuthnRecord(val user: String, val address: String, val method: AuthnMethod)

    fun addRecord(record: AuthnRecord) = AuthnSettings(authnRecords + record)
}
