package xtdb.util

import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.atomic.AtomicReference

class SimulationClock(
    startTime: Instant = Instant.parse("2020-01-01T00:00:00Z")
) : InstantSource {
    private val currentTime = AtomicReference(startTime)

    override fun instant(): Instant = currentTime.get()

    fun advanceBy(duration: Duration): Instant =
        currentTime.updateAndGet { it + duration }

    fun advanceTo(instant: Instant): Instant {
        require(!instant.isBefore(currentTime.get())) {
            "Cannot move time backwards"
        }
        currentTime.set(instant)
        return instant
    }
}
