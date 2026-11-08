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

        override fun adapterFor(entity: EntityRef) = map[entity]

        override fun assign(
            entity: EntityRef,
            adapter: AdapterId,
        ) {
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

    /** What one run of the criterion observed, so the assertions can be applied to a faulty switch too. */
    private class Observed(
        val inFlight: DocRef,
        val newE1: DocRef,
        val newE2: DocRef,
        val stubBefore: Map<EntityRef, List<DocRef>>,
    )

    private fun scenario(failover: () -> Unit): Observed {
        assignments.map[e1] = a.adapterId
        assignments.map[e2] = a.adapterId
        val inFlight = DocRef(UUID.randomUUID(), 1)
        a.transientNext = true
        send(e1, inFlight) shouldBe TransportOutcome.TransientFailure
        a.transientNext = false
        stub.getOrPut(e2) { mutableListOf() }
        val before = stubState()
        failover()
        val newE1 = DocRef(UUID.randomUUID(), 1)
        val newE2 = DocRef(UUID.randomUUID(), 1)
        send(e1, newE1)
        send(e2, newE2)
        send(e1, inFlight) // the in-flight version completes
        return Observed(inFlight, newE1, newE2, before)
    }

    /** The assertions of INV-AC-18634; they must fail for a switch that also moves E2. */
    private fun assertCriterion(o: Observed) {
        b.seen shouldContainExactly listOf(o.newE1.docUuid)
        a.seen shouldContainExactly listOf(o.inFlight.docUuid, o.newE2.docUuid, o.inFlight.docUuid)
        a.seen.intersect(b.seen.toSet()).isEmpty() shouldBe true
        assignments.map[e2] shouldBe a.adapterId
        assignments.map[e1] shouldBe b.adapterId
        // the stub state of the earlier docs is unchanged by the switch; only the new despatches were added
        stubState().getValue(e1).toSet() shouldBe setOf(o.inFlight, o.newE1)
        stubState().getValue(e2) shouldBe listOf(o.newE2)
        o.stubBefore.getValue(e1) shouldBe listOf(o.inFlight)
        o.stubBefore.getValue(e2) shouldBe emptyList()
    }

    private val failover = FailoverAdapter(SwitchEntityAdapter(registry, assignments))

    @Test
    fun `INV-AC-18634 failover of E1 moves only E1 new despatches and leaves the in-flight version on A`() {
        assertCriterion(scenario { failover.failover(e1, b.adapterId) })
    }

    @Test
    fun `INV-AC-18634 look-alike a switch that moves E2 as well fails the criterion assertions`() {
        val faulty =
            FailoverAdapter(
                SwitchEntityAdapter(
                    registry,
                    object : EntityAdapterAssignments by assignments {
                        override fun assign(
                            entity: EntityRef,
                            adapter: AdapterId,
                        ) {
                            assignments.assign(entity, adapter)
                            assignments.assign(e2, adapter) // the defect: E2 moves with E1
                        }
                    },
                ),
            )
        val observed = scenario { faulty.failover(e1, b.adapterId) }
        shouldThrow<AssertionError> { assertCriterion(observed) }
        a.seen shouldNotContain observed.newE2.docUuid
    }

    @Test
    fun `a repeat is idempotent and an unregistered target is refused`() {
        assignments.map[e1] = a.adapterId
        failover.failover(e1, b.adapterId)
        failover.failover(e1, b.adapterId)
        assignments.map[e1] shouldBe b.adapterId
        shouldThrow<AdapterNotRegistered> { failover.failover(e1, AdapterId("A2")) }
        assignments.map[e1] shouldBe b.adapterId
    }
}
