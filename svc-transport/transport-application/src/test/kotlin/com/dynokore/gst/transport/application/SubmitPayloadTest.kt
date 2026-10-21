package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.AttemptCounts
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.OutcomeKind
import com.dynokore.gst.transport.domain.TransportOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SubmitPayloadTest {
    private class Port(
        override val adapterId: AdapterId,
        var next: () -> TransportOutcome = { TransportOutcome.Accepted("ack-${adapterId.value}") },
    ) : AccessPointPort {
        var calls = 0

        override fun submit(
            doc: DocRef,
            payload: String,
        ): TransportOutcome {
            calls++
            return next()
        }
    }

    private class Log : AttemptLog {
        data class Row(
            val id: UUID,
            val doc: DocRef,
            val adapter: AdapterId,
            var outcome: TransportOutcome? = null,
        )

        val rows = mutableListOf<Row>()

        override fun adapterInFlight(doc: DocRef): AdapterId? = rows.firstOrNull { it.doc == doc }?.adapter

        override fun settledOutcome(doc: DocRef): TransportOutcome? =
            rows
                .firstOrNull { it.doc == doc && it.outcome != null && it.outcome != TransportOutcome.TransientFailure }
                ?.outcome

        override fun begin(
            entity: EntityRef,
            doc: DocRef,
            adapter: AdapterId,
            at: Instant,
        ): UUID = UUID.randomUUID().also { rows += Row(it, doc, adapter) }

        override fun complete(
            attempt: UUID,
            outcome: TransportOutcome,
            at: Instant,
        ) {
            rows.first { it.id == attempt }.outcome = outcome
        }

        override fun counts(
            adapter: AdapterId,
            since: Instant,
        ): AttemptCounts = AttemptCounts(0, 0)
    }

    private class Assignments : EntityAdapterAssignments {
        val map = mutableMapOf<EntityRef, AdapterId>()

        override fun adapterFor(entity: EntityRef): AdapterId? = map[entity]

        override fun assign(
            entity: EntityRef,
            adapter: AdapterId,
        ) {
            map[entity] = adapter
        }
    }

    private val a = Port(AdapterId("a"))
    private val b = Port(AdapterId("b"))
    private val registry =
        object : AccessPointAdapters {
            override fun find(id: AdapterId): AccessPointPort? = listOf(a, b).firstOrNull { it.adapterId == id }

            override fun ids(): Set<AdapterId> = setOf(a.adapterId, b.adapterId)
        }
    private val log = Log()
    private val assignments = Assignments()
    private val entity = EntityRef("E-001")
    private val submit = SubmitPayload(registry, log, assignments, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))

    private fun doc() = DocRef(UUID.randomUUID(), 1)

    @Test
    fun `an entity without an adapter is refused and nothing is logged`() {
        shouldThrow<NoAdapterConfigured> { submit.submit(entity, doc(), "x") }
        log.rows.size shouldBe 0
    }

    @Test
    fun `a repeat of a settled version returns the original outcome with no second egress`() {
        assignments.assign(entity, a.adapterId)
        val doc = doc()
        val first = submit.submit(entity, doc, "x")
        submit.submit(entity, doc, "x") shouldBe first
        a.calls shouldBe 1
        log.rows.size shouldBe 1
    }

    @Test
    fun `a transient failure is retried on the adapter of the first attempt after a cut-over`() {
        assignments.assign(entity, a.adapterId)
        val doc = doc()
        a.next = { TransportOutcome.TransientFailure }
        submit.submit(entity, doc, "x") shouldBe TransportOutcome.TransientFailure
        SwitchEntityAdapter(registry, assignments).switch(entity, b.adapterId)
        a.next = { TransportOutcome.Accepted("late") }
        submit.submit(entity, doc, "x") shouldBe TransportOutcome.Accepted("late")
        b.calls shouldBe 0
        submit.submit(entity, doc(), "x") shouldBe TransportOutcome.Accepted("ack-b")
        log.rows.map { it.adapter.value } shouldBe listOf("a", "a", "b")
    }

    @Test
    fun `an adapter that throws is a transient failure and the attempt is completed`() {
        assignments.assign(entity, a.adapterId)
        a.next = { error("boom") }
        submit.submit(entity, doc(), "x") shouldBe TransportOutcome.TransientFailure
        log.rows
            .single()
            .outcome
            ?.kind shouldBe OutcomeKind.TRANSIENT_FAILURE
    }

    @Test
    fun `switching to an unregistered adapter is refused`() {
        shouldThrow<AdapterNotRegistered> {
            SwitchEntityAdapter(
                registry,
                assignments,
            ).switch(entity, AdapterId("zzz"))
        }
        assignments.map.size shouldBe 0
    }
}
