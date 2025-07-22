package xtdb.trie

import com.carrotsearch.hppc.IntArrayList
import org.apache.arrow.memory.util.ArrowBufPointer
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.ArrowType.FixedSizeBinary
import xtdb.arrow.VectorReader
import xtdb.toArrowType
import xtdb.trie.HashTrie.Companion.LEVEL_WIDTH
import xtdb.trie.HashTrie.Companion.bucketFor
import java.nio.ByteBuffer

private const val LOG_LIMIT = 64
private const val PAGE_LIMIT = 1024

// hashReader is assumed to be a FixedSizeBinary vector
// for iids this is ArrowType.FixedSizeBinary(16)
// for hashes this is ArrowType.Int
class MemoryHashTrie(override val rootNode: Node, val hashReader: VectorReader) : HashTrie<MemoryHashTrie.Node, MemoryHashTrie.Leaf> {

    private val max_level : Int

    init {
        max_level =  when(val type = hashReader.field.toArrowType()) {
            is FixedSizeBinary -> type.byteWidth * 8 / LEVEL_WIDTH
            is ArrowType.Int -> type.bitWidth / LEVEL_WIDTH
            else -> throw IllegalArgumentException("Unsupported Arrow type for hashReader: ${hashReader.field.toArrowType()}")
        }
    }

    sealed interface Node : HashTrie.Node<Node> {
        fun add(trie: MemoryHashTrie, newIdx: Int): Node

        fun compactLogs(trie: MemoryHashTrie): Node
    }

    @Suppress("unused")
    class Builder(private val hashReader: VectorReader) {
        private var logLimit = LOG_LIMIT
        private var pageLimit = PAGE_LIMIT
        private var rootPath = ByteArray(0)

        fun setLogLimit(logLimit: Int) = this.apply { this.logLimit = logLimit }
        fun setPageLimit(pageLimit: Int) = this.apply { this.pageLimit = pageLimit }
        fun setRootPath(path: ByteArray) = this.apply { this.rootPath = path }
        fun build(): MemoryHashTrie = MemoryHashTrie(Leaf(logLimit, pageLimit, rootPath), hashReader)
    }

    operator fun plus(idx: Int) = MemoryHashTrie(rootNode.add(this, idx), hashReader)

    @Suppress("unused")
    fun withIidReader(hashReader: VectorReader) = MemoryHashTrie(rootNode, hashReader)

    fun compactLogs() = MemoryHashTrie(rootNode = rootNode.compactLogs(this), hashReader)

    private fun bucketFor(idx: Int, level: Int, reusePtr: ArrowBufPointer): Int =
        bucketFor(hashReader.getPointer(idx, reusePtr), level).toInt()

    private fun compare(leftIdx: Int, rightIdx: Int, leftPtr: ArrowBufPointer, rightPtr: ArrowBufPointer): Int {
        val cmp =
            hashReader.getPointer(leftIdx, leftPtr)
                .compareTo(hashReader.getPointer(rightIdx, rightPtr))

        return if (cmp != 0) cmp else rightIdx compareTo leftIdx
    }

    fun findCandidates (value: ByteArray) : IntArray{
        var node = rootNode
        while (node is Branch) {
            val bucket = bucketFor(value, node.path.size) as Int
            val child = node.hashChildren[bucket] ?: return IntArray(0)
            node = child
        }
        assert(node is Leaf) { "Expected a Leaf node, but found: $node" }
        return (node as Leaf).data
    }

    fun findCandidates(value: Int) : IntArray =
        findCandidates(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())

    // This assumes the trie has been compacted
    fun findValue (value: ByteArray) : Int {
        val valueByteBuffer by lazy { ByteBuffer.wrap(value) }
        for (idx in findCandidates(value)) {
            if (hashReader.getBytes(idx).compareTo(valueByteBuffer) == 0) {
                return idx
            }
        }
        return -1
    }

    fun findValue(value: Int) : Int =
        findValue(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())

    class Branch(
        private val logLimit: Int,
        private val pageLimit: Int,
        override val path: ByteArray,
        override val hashChildren: Array<Node?>,
    ) : Node {
        private val addPtr = ArrowBufPointer()

        override fun add(trie: MemoryHashTrie, newIdx: Int): Node {
            val bucket = trie.bucketFor(newIdx, path.size, addPtr)

            val newChildren = hashChildren.indices
                .map { childIdx ->
                    var child = hashChildren[childIdx]
                    if (bucket == childIdx) {
                        child = child ?: Leaf(logLimit, pageLimit, conjPath(path, childIdx.toByte()))
                        child = child.add(trie, newIdx)
                    }
                    child
                }.toTypedArray()

            return Branch(logLimit, pageLimit, path, newChildren)
        }

        override fun compactLogs(trie: MemoryHashTrie) =
            Branch(logLimit, pageLimit, path, hashChildren.map { child -> child?.compactLogs(trie) }.toTypedArray())
    }

    class Leaf(
        private val logLimit: Int,
        private val pageLimit: Int,
        override val path: ByteArray,
        val data: IntArray = IntArray(0),
        val log: IntArray = IntArray(logLimit),
        private val logCount: Int = 0,
        private var sortedData: IntArray? = null,
    ) : Node {

        override val hashChildren = null

        fun mergeSort(trie: MemoryHashTrie): IntArray {
            if (log.isEmpty()) return data
            return sortedData ?: mergeSort(trie, data, sortLog(trie, log, logCount), logCount)
        }

        private fun mergeSort(trie: MemoryHashTrie, data: IntArray, log: IntArray, logCount: Int): IntArray {
            val leftPtr = ArrowBufPointer()
            val logPtr = ArrowBufPointer()
            val dataCount = data.size

            val res = IntArrayList(data.size + logCount)
            var dataIdx = 0
            var logIdx = 0

            while (true) {
                if (dataIdx == dataCount) {
                    for (idx in logIdx..<logCount) res.add(log[idx])
                    break
                }

                if (logIdx == logCount) {
                    for (idx in dataIdx..<dataCount) res.add(data[idx])
                    break
                }

                val dataKey = data[dataIdx]
                val logKey = log[logIdx]

                if (trie.compare(dataKey, logKey, leftPtr, logPtr) < 0) {
                    res.add(dataKey)
                    dataIdx++
                } else {
                    res.add(logKey)
                    logIdx++
                }
            }

            return res.toArray().also { sortedData = it }
        }

        private fun sortLog(trie: MemoryHashTrie, log: IntArray, logCount: Int): IntArray {
            val leftPtr = ArrowBufPointer()
            val rightPtr = ArrowBufPointer()
            return log.take(logCount).sortedWith { leftKey: Int, rightKey: Int ->
                trie.compare(leftKey, rightKey, leftPtr, rightPtr)
            }.toIntArray()
        }


        private fun idxBuckets(trie: MemoryHashTrie, idxs: IntArray, path: ByteArray): Array<IntArray?> {
            val entryGroups = arrayOfNulls<IntArrayList>(LEVEL_WIDTH)
            val ptr = ArrowBufPointer()

            for (i in idxs) {
                val groupIdx = trie.bucketFor(i, path.size, ptr)
                val group = entryGroups[groupIdx] ?: IntArrayList().also { entryGroups[groupIdx] = it }
                group.add(i)
            }

            return entryGroups.map { b -> b?.toArray() }.toTypedArray()
        }

        override fun compactLogs(trie: MemoryHashTrie): Node {
            if (logCount == 0) return this

            val data = if (sortedData != null) sortedData as IntArray else mergeSort(trie, data, sortLog(trie, log, logCount), logCount)
            val log = IntArray(logLimit)
            val logCount = 0

            return if (data.size > pageLimit && path.size < trie.max_level) {
                val childBuckets = idxBuckets(trie, data, path)

                val childNodes = childBuckets
                    .mapIndexed<IntArray?, Node?> { childIdx, childBucket ->
                        if (childBucket == null) null
                        else
                            Leaf(logLimit, pageLimit, conjPath(path, childIdx.toByte()), childBucket)
                    }.toTypedArray()

                Branch(logLimit, pageLimit, path, childNodes)

            } else Leaf(logLimit, pageLimit, path, data, log, logCount)
        }

        override fun add(trie: MemoryHashTrie, newIdx: Int): Node {
            var logCount = logCount
            log[logCount++] = newIdx
            val newLeaf = Leaf(logLimit, pageLimit, path, data, log, logCount)

            return if (logCount == logLimit) newLeaf.compactLogs(trie) else newLeaf
        }
    }

    companion object {
        @JvmStatic
        fun builder(hashReader: VectorReader) = Builder(hashReader)

        @JvmStatic
        @Suppress("unused")
        fun emptyTrie(hashReader: VectorReader) = builder(hashReader).build()

        private fun conjPath(path: ByteArray, idx: Byte): ByteArray {
            val currentPathLength = path.size
            val childPath = ByteArray(currentPathLength + 1)
            System.arraycopy(path, 0, childPath, 0, currentPathLength)
            childPath[currentPathLength] = idx
            return childPath
        }
    }
}
