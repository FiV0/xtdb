package xtdb.arrow

import org.apache.arrow.vector.ipc.message.ArrowRecordBatch

fun ArrowRecordBatch.retain() {
    this.buffers.forEach { it.referenceManager.retain() }
}