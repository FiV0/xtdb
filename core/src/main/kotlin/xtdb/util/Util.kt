package xtdb.util

import java.nio.ByteBuffer

fun ByteBuffer.compareUnsigned(bb2: ByteBuffer): Int {
    // TODO without slicing ?
    val thisBuffer = this.slice()
    val otherBuffer = bb2.slice()

    while (thisBuffer.hasRemaining() && otherBuffer.hasRemaining()) {
        val thisByte = thisBuffer.get().toInt() and 0xFF
        val otherByte = otherBuffer.get().toInt() and 0xFF

        if (thisByte != otherByte) {
            return thisByte.compareTo(otherByte)
        }
    }

    return thisBuffer.remaining().compareTo(otherBuffer.remaining())
}

fun ByteArray.toIntString(): String {
    return joinToString(" ") { it.toInt().toString() }
}