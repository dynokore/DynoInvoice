package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.events.ConsumedSubmissionEvents
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import com.dynokore.platform.outboxandqueue.CorrelationId as EventCorrelation

/**
 * The consumer of SubmissionDespatched (INV-REQ-439, INV-AC-22474) at the service boundary: the real handler bean over
 * the composed context and the database. The event names its aggregate by the optional member `aggregateId`.
 */
class SubmissionDespatchedBoundaryTest : BoundaryFixture() {
    private fun Boundary.deliver(
        context: ApplicationContext,
        tenant: UUID,
        data: String,
    ): HandleOutcome =
        scope.bind(TenantId(tenant), actor) {
            context.getBean("submissionDespatchedHandler", EventHandler::class.java).handle(
                Envelope(
                    id = EventId(UUID.randomUUID().toString()),
                    source = "/gst/submission-svc",
                    type = ConsumedSubmissionEvents.SUBMISSION_DESPATCHED,
                    subject = "E-001",
                    time = Instant.parse("2027-04-02T00:00:00Z"),
                    tenant = TenantId(tenant),
                    entity = EntityRef("E-001"),
                    docUuid = null,
                    transactionDate = null,
                    docSequence = null,
                    instanceId = "submission-svc-0",
                    correlation = EventCorrelation("c"),
                    causation = CausationId("k"),
                    dataJson = data,
                ),
            )
        }

    private fun inContext(
        dir: Path,
        block: (ApplicationContext) -> Unit,
    ) {
        AggregationContext.online(dir).run { context ->
            context.startupFailure?.let { throw it }
            block(context)
        }
    }

    private fun event(aggregateId: String? = null) =
        """{"docUuid":"${UUID.randomUUID()}","version":1,"attemptNumber":1,"payloadSha256":"${"a".repeat(64)}"""" +
            (if (aggregateId == null) "" else ""","aggregateId":$aggregateId""") + "}"

    private fun transmittedAt(
        tenant: UUID,
        aggregateId: String,
    ): List<String> =
        query(
            "select coalesce(transmitted_at::text, 'null') from aggregation.aggregate " +
                "where tenant_id = ? and aggregate_id = '$aggregateId'",
            tenant,
        ) { it.getString(1) }

    private fun rowsExcept(
        tenant: UUID,
        aggregateId: String,
    ): List<String> =
        query(
            "select to_jsonb(a)::text from aggregation.aggregate a where tenant_id = ? " +
                "and aggregate_id::text <> '$aggregateId' order by aggregate_id",
            tenant,
        ) { it.getString(1) }

    /** The marked row in every column other than transmitted_at. */
    private fun contentOf(
        tenant: UUID,
        aggregateId: String,
        whole: Boolean = false,
    ): List<String> =
        query(
            "select (to_jsonb(a)${if (whole) "" else " - 'transmitted_at'"})::text from aggregation.aggregate a " +
                "where tenant_id = ? and aggregate_id = '$aggregateId'",
            tenant,
        ) { it.getString(1) }

    private fun handedOn(
        boundary: Boundary,
        tenant: UUID,
        key: String = "K-1",
    ): String {
        val receipt = json(boundary.send(tenant, body(sourceKey = key)).also { it.status shouldBe 202 })
        closeWindows(tenant)
        return receipt["aggregateId"].asString()
    }

    @Test
    fun `INV-AC-22474 the event marks the aggregate once and a redelivery leaves the row equal`(
        @TempDir dir: Path,
    ): Unit =
        inContext(dir) { context ->
            val boundary = Boundary(context)
            val tenant = boundary.tenant()
            val id = handedOn(boundary, tenant)
            boundary.handOn(tenant).size shouldBe 1
            val sibling = handedOn(boundary, tenant, "K-2")
            val bystander = boundary.tenant().also { handedOn(boundary, it) }
            transmittedAt(tenant, id) shouldBe listOf("null")
            val others = rowsExcept(tenant, id)
            val foreign = rowsExcept(bystander, "")
            val content = contentOf(tenant, id)
            boundary.deliver(context, tenant, event("\"$id\"")) shouldBe HandleOutcome.APPLIED
            val marked = transmittedAt(tenant, id).single()
            (marked != "null") shouldBe true
            val row = contentOf(tenant, id, true)
            boundary.deliver(context, tenant, event("\"$id\"")) shouldBe HandleOutcome.IGNORED
            transmittedAt(tenant, id) shouldBe listOf(marked)
            contentOf(tenant, id, true) shouldBe row
            transmittedAt(tenant, sibling) shouldBe listOf("null")
            rowsExcept(tenant, id) shouldBe others
            rowsExcept(bystander, "") shouldBe foreign
            contentOf(tenant, id) shouldBe content
        }

    @Test
    fun `INV-AC-22474 an event without aggregateId, an unknown id and another tenant's aggregate mark no row`(
        @TempDir dir: Path,
    ): Unit =
        inContext(dir) { context ->
            val boundary = Boundary(context)
            val mine = boundary.tenant()
            val other = boundary.tenant()
            val own = handedOn(boundary, mine)
            val foreign = handedOn(boundary, other)
            boundary.deliver(context, mine, event()) shouldBe HandleOutcome.IGNORED
            boundary.deliver(context, mine, event("null")) shouldBe HandleOutcome.IGNORED
            boundary.deliver(context, mine, event("\"${UUID.randomUUID()}\"")) shouldBe HandleOutcome.IGNORED
            boundary.deliver(context, mine, event("\"not-a-uuid\"")) shouldBe HandleOutcome.IGNORED
            boundary.deliver(context, mine, event("\"$foreign\"")) shouldBe HandleOutcome.IGNORED
            transmittedAt(mine, own) shouldBe listOf("null")
            transmittedAt(other, foreign) shouldBe listOf("null")
        }

    @Test
    fun `INV-AC-22474 an aggregateId that is the number 12 or the empty string is a contract violation`(
        @TempDir dir: Path,
    ): Unit =
        inContext(dir) { context ->
            val boundary = Boundary(context)
            val tenant = boundary.tenant()
            val id = handedOn(boundary, tenant)
            listOf("12", "\"\"", "true", "[\"$id\"]", "{}").forEach {
                shouldThrow<ContractViolation> { boundary.deliver(context, tenant, event(it)) }
            }
            transmittedAt(tenant, id) shouldBe listOf("null")
        }

    @Test
    fun `INV-AC-22474 the consumer is on the topic gst submission submission-despatched v1`(
        @TempDir dir: Path,
    ): Unit =
        inContext(dir) { context ->
            val subscription =
                context.getBean("submissionDespatchedSubscription", ConsumerSubscription::class.java)
            subscription.topic shouldBe "gst.submission.submission-despatched.v1"
            ConsumedSubmissionEvents.SUBMISSION_DESPATCHED shouldBe
                "com.dynokore.gst.submission.SubmissionDespatched.v1"
        }

    @Test
    fun `INV-AC-22474 the runtime login may update transmitted_at and no content column of aggregate`(
        @TempDir dir: Path,
    ): Unit =
        inContext(dir) {
            AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
                connection.createStatement().use {
                    it.executeUpdate("update aggregation.aggregate set transmitted_at = now() where false") shouldBe 0
                }
                listOf("entity_ref", "document_number", "tenant_id").forEach { column ->
                    shouldThrow<SQLException> {
                        connection.createStatement().use {
                            it.executeUpdate("update aggregation.aggregate set $column = $column where false")
                        }
                    }.sqlState shouldBe "42501"
                }
            }
        }
}
