package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.queue.DespatchCycle
import com.dynokore.gst.submission.application.queue.DespatchedVersion
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** The version a [com.dynokore.gst.submission.application.queue.DespatchCycle] turn reports (INV-REQ-554). */
class DespatchCycleItemDbTest {
    private val accepted = TransportResult.Accepted("AP-1", Instant.parse("2026-11-01T10:00:05Z"), "ok".toByteArray())

    private fun run(
        dir: Path,
        block: (OutcomeFixture) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> block(OutcomeFixture(context)) }
    }

    /** Two fresh Doc UUIDs ("doc-a", "doc-b" of the criterion; runs share a database), queued in this order. */
    private class Pair2(
        val tenant: UUID = UUID.randomUUID(),
        val docA: String = UUID.randomUUID().toString(),
        val docB: String = UUID.randomUUID().toString(),
    )

    private fun twoQueued(f: OutcomeFixture): Pair2 =
        Pair2().also {
            f.queued(it.tenant, it.docA)
            f.queued(it.tenant, it.docB)
        }

    @Test
    fun `INV-AC-20843 a runOnceItem returns the despatched versions in claim order then null`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val docs = twoQueued(f)
        val cycle = f.cycle(FakeTransport(accepted))

        cycle.runOnceItem() shouldBe DespatchedVersion(docs.docA, 1)
        cycle.runOnceItem() shouldBe DespatchedVersion(docs.docB, 1)
        cycle.runOnceItem() shouldBe null
    }

    @Test
    fun `INV-AC-20843 b runOnce gives true true false and the same stored attempt rows`(
        @TempDir first: Path,
        @TempDir second: Path,
    ) {
        val results = mutableListOf<Boolean>()

        fun rowsOf(
            dir: Path,
            turn: (DespatchCycle) -> Unit,
        ): List<Pair<Int, Int?>> {
            var rows = emptyList<Pair<Int, Int?>>()
            run(dir) { f ->
                val docs = twoQueued(f)
                val cycle = f.cycle(FakeTransport(accepted))
                repeat(3) { turn(cycle) }
                rows =
                    listOf(docs.docA, docs.docB).withIndex().flatMap { (i, doc) ->
                        f.attempts(docs.tenant, doc).map { i to it.attemptNumber }
                    }
            }
            return rows
        }

        val viaItem = rowsOf(first) { it.runOnceItem() }
        val viaOnce = rowsOf(second) { results += it.runOnce() }

        results shouldBe listOf(true, true, false)
        viaOnce shouldBe viaItem
        viaOnce.size shouldBe 2
    }

    @Test
    fun `INV-AC-20843 c an item that is neither eligible nor recoverable ends in the same error`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.seed(tenant, doc, LifecycleState.REJECTED)
        f.asTenant(tenant) { f.workQueue.enqueue(QueueEntry(doc, Lane.LIVE, """{"docUuid":"$doc","version":1}""")) }
        val cycle = f.cycle(FakeTransport(accepted))

        val failure = shouldThrow<IllegalStateException> { cycle.runOnceItem() }

        failure.message.orEmpty().endsWith("holds a version in state REJECTED: stop") shouldBe true
        f.attempts(tenant, doc).size shouldBe 0
    }
}
