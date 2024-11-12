package xtdb.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.InetAddress
import java.nio.ByteBuffer

fun InetAddress.isIPv4(): Boolean {
    return this.address.size == 4
}

fun InetAddress.isIPv6(): Boolean {
    return this.address.size == 16
}

@Serializable(with = InetAddressRangeSerializer::class)
data class InetAddressRange (val baseAddress: InetAddress, val mask: Int) {

    fun contains(address: InetAddress): Boolean {
        // Check if the IP versions match (both IPv4 or both IPv6)
        if (baseAddress.address.size != address.address.size) {
            return false
        }

        // Apply the subnet mask and compare the network parts
        val maskBytes = if (baseAddress.isIPv4()) getIPv4Mask(mask) else getIPv6Mask(mask)
        val baseAddressMasked = applyMask(baseAddress, maskBytes)
        val addressMasked = applyMask(address, maskBytes)

        // Check if the masked addresses are equal
        return baseAddressMasked.contentEquals(addressMasked)
    }

    private fun getIPv4Mask(mask: Int): ByteArray {
        val maskValue = (0xFFFFFFFF.toInt() shl (32 - mask)).toLong() // Mask for IPv4 as a 32-bit value
        return ByteBuffer.allocate(4).putInt(maskValue.toInt()).array()
    }

    private fun getIPv6Mask(mask: Int): ByteArray {
        val maskBytes = ByteArray(16)
        for (i in 0 until mask / 8) {
            maskBytes[i] = 0xFF.toByte()
        }
        if (mask % 8 != 0) {
            maskBytes[mask / 8] = ((0xFF shl (8 - (mask % 8))) and 0xFF).toByte()
        }
        return maskBytes
    }

    private fun applyMask(address: InetAddress, maskBytes: ByteArray): ByteArray {
        val addressBytes = address.address
        val maskedAddress = ByteArray(addressBytes.size)
        for (i in addressBytes.indices) {
            maskedAddress[i] = (addressBytes[i].toInt() and maskBytes[i].toInt()).toByte()
        }
        return maskedAddress
    }

    companion object {
        /**
         * Parses a CIDR notation string and returns an InetAddressRange.
         * Example: "192.168.1.0/24" or "2001:db8::/32"
         */
        fun parse(cidr: String): InetAddressRange {
            val parts = cidr.split("/")
            require(parts.size == 2) { "Invalid CIDR notation: $cidr" }

            val address = InetAddress.getByName(parts[0])
            val mask = parts[1].toInt()

            // Validate mask range
            if (address.isIPv4() && (mask < 0 || mask > 32)) {
                throw IllegalArgumentException("Invalid IPv4 mask length: $mask")
            }
            if (address.isIPv6() && (mask < 0 || mask > 128)) {
                throw IllegalArgumentException("Invalid IPv6 mask length: $mask")
            }

            return InetAddressRange(address, mask)
        }
    }
}

object InetAddressRangeSerializer : KSerializer<InetAddressRange> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("InetAddressRange", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: InetAddressRange) {
        val cidrString = "${value.baseAddress.hostAddress}/${value.mask}"
        encoder.encodeString(cidrString)
    }

    override fun deserialize(decoder: Decoder): InetAddressRange {
        val cidrString = decoder.decodeString()
        return InetAddressRange.parse(cidrString)
    }
}