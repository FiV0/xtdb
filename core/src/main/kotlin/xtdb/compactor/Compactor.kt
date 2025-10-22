package xtdb.compactor

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.time.withTimeout
import xtdb.api.log.Log
import xtdb.api.log.Log.Message.TriesAdded
import xtdb.api.storage.Storage
import xtdb.arrow.Relation
import xtdb.compactor.PageTree.Companion.asTree
import xtdb.database.Database
import xtdb.log.proto.TrieDetails
import xtdb.log.proto.TrieMetadata
import xtdb.segment.BufferPoolSegment.Companion.open
import xtdb.table.TableRef
import xtdb.trie.*
import xtdb.util.*
import java.nio.channels.ClosedByInterruptException
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.use

private typealias JobKey = Pair<TableRef, TrieKey>

private val LOGGER = Compactor::class.logger

interface Compactor : AutoCloseable {

    interface Job {
        val table: TableRef
        val trieKeys: List<TrieKey>
        val part: ByteArray
        val outputTrieKey: Trie.Key
        val partitionedByRecency: Boolean
        val inputTries: Any
    }

    interface JobCalculator {
        fun availableJobs(trieCatalog: TrieCatalog): Collection<Job>
    }

    interface ForDatabase : AutoCloseable {
        fun signalBlock()
        fun compactAll(timeout: Duration? = null)
    }

    fun openForDatabase(db: Database): ForDatabase

    interface Driver : AutoCloseable {
        suspend fun launchIn(scope: CoroutineScope, f: suspend CoroutineScope.() -> Unit)
        suspend fun executeJob(job: Job): TriesAdded
        suspend fun appendMessage(triesAdded: TriesAdded): Log.MessageMetadata
        fun onDone(doneCh: Channel<JobKey>): SelectClause1<JobKey>
        fun onWakeup(wakeup: Channel<Unit>): SelectClause1<Unit>

        interface Factory {
            fun create(db: Database): Driver
        }

        companion object {
            @JvmStatic
            fun real(meterRegistry: MeterRegistry?, pageSize: Int, recencyPartition: RecencyPartition?) =
                object : Factory {
                    override fun create(db: Database) = object : Driver {
                        private val al = db.allocator.openChildAllocator("compactor")
                            .also { meterRegistry?.register(it) }

                        private val log = db.log
                        private val bp = db.bufferPool
                        private val mm = db.metadataManager

                        private val trieWriter = PageTrieWriter(al, bp, calculateBlooms = true)
                        private val segMerge = SegmentMerge(al)

                        override suspend fun launchIn(scope: CoroutineScope, f: suspend CoroutineScope.() -> Unit) =
                            scope.launch(block = f).let { }

                        private fun Job.trieDetails(
                            trieKey: TrieKey,
                            dataFileSize: FileSize,
                            trieMetadata: TrieMetadata?
                        ) =
                            TrieDetails.newBuilder()
                                .setTableName(table.sym.toString())
                                .setTrieKey(trieKey)
                                .setDataFileSize(dataFileSize)
                                .setTrieMetadata(trieMetadata)
                                .build()

                        override suspend fun executeJob(job: Job): TriesAdded =
                            try {
                                LOGGER.debug("compacting '${job.table.sym}' '${job.trieKeys}' -> ${job.outputTrieKey}")

                                job.trieKeys.safeMap { open(al, bp, mm, job.table, it) }.useAll { segs ->

                                    val recencyPartitioning =
                                        if (job.partitionedByRecency) SegmentMerge.RecencyPartitioning.Partition
                                        else SegmentMerge.RecencyPartitioning.Preserve(job.outputTrieKey.recency)

                                    val addedTries =
                                        segMerge.mergeSegments(segs, job.part, recencyPartitioning, recencyPartition)
                                            .useAll { mergeRes ->
                                                mergeRes.map {
                                                    it.openForRead().use { mergeReadCh ->
                                                        Relation.loader(al, mergeReadCh).use { loader ->
                                                            val trieKey =
                                                                job.outputTrieKey.copy(recency = it.recency).toString()

                                                            val (dataFileSize, trieMetadata) =
                                                                trieWriter.writePageTree(
                                                                    job.table, trieKey,
                                                                    loader, it.leaves.asTree,
                                                                    pageSize
                                                                )

                                                            LOGGER.debug("compacted '${job.table.sym}' -> '${job.outputTrieKey}'")

                                                            job.trieDetails(trieKey, dataFileSize, trieMetadata)
                                                        }
                                                    }
                                                }
                                            }
                                    return TriesAdded(Storage.VERSION, bp.epoch, addedTries)
                                }
                            } catch (e: ClosedByInterruptException) {
                                throw InterruptedException(e.message)
                            } catch (e: InterruptedException) {
                                throw e
                            } catch (e: Throwable) {
                                LOGGER.error(e) { "error running compaction job: ${job.table.sym}/${job.outputTrieKey}, files in job: '${job.trieKeys}'" }
                                throw e
                            }

                        override suspend fun appendMessage(triesAdded: TriesAdded): Log.MessageMetadata =
                            log.appendMessage(triesAdded).await()

                        override fun onDone(doneCh: Channel<JobKey>): SelectClause1<JobKey> = doneCh.onReceive
                        override fun onWakeup(wakeup: Channel<Unit>): SelectClause1<Unit> = wakeup.onReceive

                        override fun close() = al.close()
                    }
                }
        }
    }

    class Impl(
        private val driverFactory: Driver.Factory,
        private val meterRegistry: MeterRegistry?,
        private val jobCalculator: JobCalculator,
        private val ignoreSignalBlock: Boolean,
        threadCount: Int,
    ) : Compactor {

        internal val scope = CoroutineScope(Dispatchers.Default)

        internal val jobsScope =
            CoroutineScope(
                Dispatchers.Default.limitedParallelism(threadCount, "compactor")
                        + SupervisorJob(scope.coroutineContext.job)
            )

        override fun openForDatabase(db: Database) = object : ForDatabase {

            private val trieCatalog = db.trieCatalog

            private val driver = driverFactory.create(db)

            private val wakeup = Channel<Unit>(1, onBufferOverflow = DROP_OLDEST)
            private val idle = Channel<Unit>()

            @Volatile
            private var availableJobs = emptyMap<JobKey, Job>()

            private val queuedJobs = mutableSetOf<JobKey>()

            private val jobTimer: Timer? = meterRegistry?.let {
                Timer.builder("compactor.job.timer")
                    .publishPercentiles(0.75, 0.85, 0.95, 0.98, 0.99, 0.999)
                    .register(it)
            }

            init {
                meterRegistry?.let {
                    Gauge.builder("compactor.jobs.available") { jobCalculator.availableJobs(trieCatalog).size.toDouble() }
                        .register(it)
                }

                scope.launch {
                    val doneCh = Channel<JobKey>()

                    while (true) {
                        availableJobs =
                            jobCalculator.availableJobs(trieCatalog)
                                .associateBy { JobKey(it.table, it.outputTrieKey.toString()) }

                        if (availableJobs.isEmpty() && queuedJobs.isEmpty()) {
                            LOGGER.trace("sending idle")
                            idle.trySend(Unit)
                        }

                        availableJobs.keys.forEach { jobKey ->
                            if (queuedJobs.add(jobKey)) {
                                driver.launchIn(jobsScope) {
                                    // check it's still required
                                    val job = availableJobs[jobKey]
                                    if (job != null) {
                                        val timer = meterRegistry?.let { Timer.start(it) }
                                        val triesAdded = driver.executeJob(job)
                                        jobTimer?.let { timer?.stop(it) }
                                        val messageMetadata = driver.appendMessage(triesAdded)

                                        // add the trie to the catalog eagerly so that it's present
                                        // next time we run `availableJobs` (it's idempotent)
                                        trieCatalog.addTries(
                                            job.table,
                                            triesAdded.tries,
                                            messageMetadata.logTimestamp
                                        )
                                    }

                                    doneCh.send(jobKey)
                                }
                            }
                        }

                        select {
                            driver.onDone(doneCh).invoke<JobKey> {
                                queuedJobs.remove(it)
                            }

                            driver.onWakeup(wakeup).invoke<Unit> {
                                LOGGER.trace("wakey wakey")
                            }
                        }
                    }
                }
            }

            override fun signalBlock() {
                if (!ignoreSignalBlock) wakeup.trySend(Unit)
            }

            override fun compactAll(timeout: Duration?) {
                val job = scope.launch {
                    LOGGER.trace("compactAll: waiting for idle")
                    if (timeout == null) idle.receive() else withTimeout(timeout) { idle.receive() }
                    LOGGER.trace("compactAll: idle")
                }

                wakeup.trySend(Unit)

                runBlocking { job.join() }
            }

            override fun close() {
                runBlocking {
                    withTimeoutOrNull(10.seconds) { scope.coroutineContext.job.cancelAndJoin() }
                        ?: LOGGER.warn("failed to close compactor cleanly in 10s")
                }

                driver.close()

                LOGGER.debug("compactor closed")
            }
        }
    }

    override fun close() = Unit

    companion object {
        @JvmField
        val NOOP = object : Compactor {
            override fun openForDatabase(db: Database) = object : ForDatabase {
                override fun signalBlock() = Unit
                override fun compactAll(timeout: Duration?) = Unit
                override fun close() = Unit
            }

            override fun close() = Unit
        }
    }
}
