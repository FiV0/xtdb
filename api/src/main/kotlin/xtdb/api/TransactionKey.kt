package xtdb.api

import clojure.lang.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

private val TX_ID_KEY: Keyword = Keyword.intern("tx-id")
private val SYSTEM_TIME_KEY: Keyword = Keyword.intern("system-time")


@Serializable
data class TransactionKey(val txId: Long, @Serializable(InstantSerializer::class) val systemTime: Instant) : Comparable<TransactionKey>, ILookup, Seqable {
    override fun compareTo(other: TransactionKey) = txId.compareTo(other.txId)

    fun withSystemTime(systemTime: Instant) = TransactionKey(this.txId, systemTime)

    override fun valAt(key: Any?) = valAt(key, null)

    override fun valAt(key: Any?, notFound: Any?) =
        when {
            key === TX_ID_KEY -> txId
            key === SYSTEM_TIME_KEY -> systemTime
            else -> notFound
        }

    override fun seq(): ISeq? =
        PersistentList.create(
            listOf(MapEntry.create(TX_ID_KEY, txId), MapEntry.create(SYSTEM_TIME_KEY, systemTime))
        ).seq()
}