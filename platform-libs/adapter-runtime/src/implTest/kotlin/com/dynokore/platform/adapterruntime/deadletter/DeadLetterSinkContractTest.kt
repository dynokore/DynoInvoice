package com.dynokore.platform.adapterruntime.deadletter

import com.dynokore.platform.adapterruntime.DeadLetterEntry
import com.dynokore.platform.adapterruntime.DeadLetterSink
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The port contract every [DeadLetterSink] implementation must satisfy: it must not lose or reorder an entry it
 * accepted.
 */
abstract class DeadLetterSinkContractTest {
    abstract fun sink(): DeadLetterSink

    abstract fun received(sink: DeadLetterSink): List<DeadLetterEntry>

    @Test
    fun `every sent entry is received, in order`() {
        val sink = sink()
        val entries =
            (1..3).map { n ->
                DeadLetterEntry(
                    edge = "edge-$n",
                    attempts = n,
                    failedAt = Instant.parse("2026-09-27T00:0$n:00Z"),
                    failureType = "java.io.IOException",
                )
            }

        entries.forEach(sink::send)

        received(sink) shouldContainExactly entries
    }
}
