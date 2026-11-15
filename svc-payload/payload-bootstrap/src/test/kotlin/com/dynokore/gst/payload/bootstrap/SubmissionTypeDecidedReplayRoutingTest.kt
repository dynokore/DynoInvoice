package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.ReplayOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The replay routing of dead letters to the consumer of their envelope type, over the real database (P109). */
class SubmissionTypeDecidedReplayRoutingTest {
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000021")
    private val json = EnvelopeJson()
    private val mapper = ObjectMapper()

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val queue: WorkQueue = context.getBean(WorkQueue::class.java)
        val replay: ReplayDelivery = context.getBean(ReplayDelivery::class.java)
    }

    private fun run(
        dir: Path,
        block: Boundary.(TenantId) -> Unit,
    ) {
        clearQueue() // the database is shared: no item of another test may be claimed here
        PayloadContext.online(dir).run { context -> Boundary(context).block(TenantId(UUID.randomUUID())) }
    }

    private fun envelope(
        tenant: TenantId,
        type: String,
        data: String,
    ): String =
        json.write(
            Envelope(
                id = EventId("ev-" + UUID.randomUUID()),
                source = "/gst/test",
                type = type,
                subject = doc.toString(),
                time = Instant.parse("2026-10-20T10:00:00Z"),
                tenant = tenant,
                entity = EntityRef("E-1"),
                docUuid = DocUuid(doc),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = 1,
                instanceId = "test-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = data,
            ),
        )

    private fun decision(tenant: TenantId) =
        envelope(
            tenant,
            SubmissionTypeDecidedConsumerConfiguration.TYPE,
            """{"docUuid":"$doc","version":1,"docsequence":1,"submissionType":"TYPE_2"}""",
        )

    private fun version(tenant: TenantId) =
        envelope(
            tenant,
            SubmissionTypeDecidedConsumerConfiguration.VERSION_FACTS_TYPE,
            """{"docUuid":"$doc","version":1,"canonicalDocument":{"currency":{"value":"SGD"},""" +
                """"totals":{"value":{"totalIncludingTax":"5.00"}}}}""",
        )

    private fun Boundary.seed(
        tenant: TenantId,
        raw: String,
    ) = scope.bind(tenant, "svc:payload-svc") {
        runner.inTransaction {
            val payload = mapper.createObjectNode().put("raw", raw).toString()
            queue.enqueue(QueueEntry(doc.toString(), Lane.REPLAY, payload, "payload-svc"))
        }
    }

    private fun count(
        table: String,
        tenant: TenantId,
    ): Int =
        PayloadDb.superuser().use { c ->
            c.prepareStatement("select count(*) from payload.$table where tenant_id = ?").use { st ->
                st.setObject(1, tenant.value)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun clearQueue() =
        PayloadDb.superuser().use { c ->
            c.createStatement().use { it.executeUpdate("delete from payload.work_queue") }
        }

    private fun Boundary.refused(
        tenant: TenantId,
        raw: String,
    ) {
        seed(tenant, raw)
        shouldThrow<IllegalStateException> { replay.runOnce() }
        count("submission_type_decision", tenant) shouldBe 0
        count("document_version_fact", tenant) shouldBe 0
        count("work_queue", tenant) shouldBe 1 // not completed: the item stays queued
        replay.runCatching { runOnce() }.isFailure shouldBe true // and is claimable again
        clearQueue()
    }

    @Test
    fun `replay routing a decision dead letter is applied by the decision consumer`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        seed(t1, decision(t1))
        replay.runOnce() shouldBe ReplayOutcome.APPLIED
        count("submission_type_decision", t1) shouldBe 1
        count("document_version_fact", t1) shouldBe 0
    }

    @Test
    fun `replay routing a version event dead letter is applied by the version facts consumer`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        seed(t1, version(t1))
        replay.runOnce() shouldBe ReplayOutcome.APPLIED
        count("document_version_fact", t1) shouldBe 1
        count("submission_type_decision", t1) shouldBe 0
    }

    @Test
    fun `replay routing an unknown type is refused and stays queued`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val type = SubmissionTypeDecidedConsumerConfiguration.TYPE
        listOf(
            decision(t1).replace(type, "com.dynokore.gst.submission.SubmissionTypeDecided.v2"),
            decision(t1).replace(type, "x$type"),
            decision(t1).replace(type, "${type}x"),
            decision(t1).replace(type, "com.dynokore.gst.other.Unknown.v1"),
        ).forEach { refused(t1, it) }
    }

    @Test
    fun `replay routing an envelope without a type is refused and stays queued`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val withoutType =
            mapper.readTree(decision(t1)).also { (it as tools.jackson.databind.node.ObjectNode).remove("type") }
        refused(t1, withoutType.toString())
        refused(t1, "not json")
    }

    @Test
    fun `replay routing an envelope of another tenant is dead lettered again`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        seed(t1, decision(TenantId(UUID.randomUUID())))
        replay.runOnce() shouldBe ReplayOutcome.REJECTED
        count("submission_type_decision", t1) shouldBe 0
    }
}
