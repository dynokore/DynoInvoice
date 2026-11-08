package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.AttemptCounts
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-18634: failoverAdapter moves one entity only; in-flight versions finish on the first adapter. */
class FailoverAdapterTest {
    /** A simulator adapter: records the Doc UUIDs it receives; the first call of `holdFirst` stays transient. */
    private class Sim(
        override val adapterId: AdapterId,
    ) : AccessPointPort {
        val seen = mutableListOf<UUID>()
        var transientNext = false

        override fun submit(
            doc: DocRef,
            payload: String,
        ): TransportOutcome {
            seen += doc.docUuid
            return if (transientNext) TransportOutcome.TransientFailure else TransportOutcome.Accepted("ack-$adapterId")
        }
    }

    private class Log : AttemptLog {
        data class Row(
            val id: UUID,
            val entity: EntityRef,
            val doc: DocRef,
            val adapter: AdapterId,
            var outcome: TransportOutcome? = null,
        )

        val rows = mutableListOf<Row>()

        override fun adapterInFlight(doc: DocRef) = rows.firstOrNull { it.doc == doc }?.adapter

        override fun entityOf(docUuid: UUID) = rows.firstOrNull { it.doc.docUuid == docUuid }?.entity

        override fun acceptedAt(doc: DocRef): Instant? = null

        override fun settledOutcome(doc: DocRef) =
            rows
                .firstOrNull { it.doc == doc && it.outcome != null && it.outcome != TransportOutcome.TransientFailure }
                ?.outcome

        override fun begin(
            entity: EntityRef,
            doc: DocRef,
            adapter: AdapterId,
            at: Instant,
        ): UUID = UUID.randomUUID().also { rows += Row(it, entity, doc, adapter) }

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
        ) = AttemptCounts(0, 0)
    }

    private class Assignments : EntityAdapterAssignments {
        val map = mutableMapOf<EntityRef, AdapterId>()
        var assigns = 0

        override fun adapterFor(entity: EntityRef) = map[entity]

        override fun assign(
            entity: EntityRef,
            adapter: AdapterId,
        ) {
            assigns++
            map[entity] = adapter
        }
    }

    private val a = Sim(AdapterId("A"))
    private val b = Sim(AdapterId("B"))
    private val registry =
        object : AccessPointAdapters {
            override fun find(id: AdapterId): AccessPointPort? = listOf(a, b).firstOrNull { it.adapterId == id }

            override fun ids() = setOf(a.adapterId, b.adapterId)
        }
    private val log = Log()
    private val assignments = Assignments()
    private val submit = SubmitPayload(registry, log, assignments, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
    private val e1 = EntityRef("E1")
    private val e2 = EntityRef("E2")

    /** The submission state held by the caller (stub): what the caller was told for each Doc UUID. */
    private val stub = mutableMapOf<EntityRef, MutableList<Pair<DocRef, TransportOutcome>>>()

    private fun send(
        entity: EntityRef,
        doc: DocRef,
    ) = submit.submit(entity, doc, "x").also { stub.getOrPut(entity) { mutableListOf() } += doc to it }

    private fun stubState() = stub.mapValues { (_, v) -> v.map { it.first }.toList() }

    private fun scenario(failover: () -> Unit): Triple<DocRef, DocRef, DocRef> {
        assignments.map[e1] = a.adapterId
        assignments.map[e2] = a.adapterId
        val inFlight = DocRef(UUID.randomUUID(), 1)
        a.transientNext = true
        send(e1, inFlight) shouldBe TransportOutcome.TransientFailure
        a.transientNext = false
        stub.getOrPut(e2) { mutableListOf() }
        val before = stubState()
        failover()
        stubState() shouldBe before
        val newE1 = DocRef(UUID.randomUUID(), 1)
        val newE2 = DocRef(UUID.randomUUID(), 1)
        send(e1, newE1)
        send(e2, newE2)
        send(e1, inFlight) // the in-flight version completes
        return Triple(inFlight, newE1, newE2)
    }

    @Test
    fun `INV-AC-18634 failover of E1 moves only E1 new despatches and leaves the in-flight version on A`() {
        val (inFlight, newE1, newE2) =
            scenario { FailoverAdapter(registry, assignments).failover(e1, a.adapterId, b.adapterId) }
        b.seen shouldContainExactly listOf(newE1.docUuid)
        a.seen shouldContainExactly listOf(inFlight.docUuid, newE2.docUuid, inFlight.docUuid)
        a.seen.intersect(b.seen.toSet()).isEmpty() shouldBe true
        assignments.map[e2] shouldBe a.adapterId
        assignments.map[e1] shouldBe b.adapterId
        stubState().getValue(e1).toSet() shouldBe setOf(inFlight, newE1)
        stubState().getValue(e2) shouldBe listOf(newE2)
    }

    @Test
    fun `INV-AC-18634 look-alike a switch that moves E2 as well is caught by the same assertions`() {
        val (_, _, newE2) =
            scenario {
                FailoverAdapter(registry, assignments).failover(e1, a.adapterId, b.adapterId)
                assignments.assign(e2, b.adapterId) // the defect
            }
        // the assertion of the criterion "the new despatch of E2 still goes through A" fails for this switch
        (newE2.docUuid in a.seen) shouldBe false
        assignments.map[e2] shouldBe b.adapterId
        a.seen shouldNotContain newE2.docUuid
    }

    @Test
    fun `a repeat for an entity already on the target is a no-op and a wrong source or unknown adapter is refused`() {
        assignments.map[e1] = a.adapterId
        val failover = FailoverAdapter(registry, assignments)
        failover.failover(e1, a.adapterId, b.adapterId) shouldBe RouteOutcome.CHANGED
        failover.failover(e1, a.adapterId, b.adapterId) shouldBe RouteOutcome.NO_OP
        assignments.assigns shouldBe 1
        shouldThrow<EntityNotOnAdapter> { failover.failover(EntityRef("E3"), a.adapterId, b.adapterId) }
        shouldThrow<AdapterNotRegistered> { failover.failover(e1, a.adapterId, AdapterId("A2")) }
        assignments.map[e1] shouldBe b.adapterId
    }
}
