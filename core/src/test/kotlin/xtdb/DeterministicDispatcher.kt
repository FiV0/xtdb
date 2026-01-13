package xtdb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import xtdb.util.SimulationClock
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

class DeterministicDispatcher(
    private val rand: Random,
    val clock: SimulationClock = SimulationClock(),
    private val timeAdvancePerYield: Duration = Duration.ofMillis(100)
) : CoroutineDispatcher() {

    constructor(seed: Int) : this(Random(seed))
    constructor(seed: Int, clock: SimulationClock) : this(Random(seed), clock)

    private data class DispatchJob(val context: CoroutineContext, val block: Runnable)

    private val jobs = mutableSetOf<DispatchJob>()

    @Volatile
    private var running = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        jobs.add(DispatchJob(context, block))

        if (!running) {
            running = true
            while (true) {
                val job = jobs.randomOrNull(rand) ?: break
                jobs.remove(job)
                job.block.run()
                clock.advanceBy(timeAdvancePerYield)
            }
            running = false
        }
    }
}
