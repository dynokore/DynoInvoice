package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.OffboardingExportRequestedHandler
import com.dynokore.gst.evidence.application.export.ExportClassSources
import com.dynokore.gst.evidence.application.export.ExportJob
import com.dynokore.gst.evidence.application.export.ExportJobs
import com.dynokore.gst.evidence.application.export.ExportRequest
import com.dynokore.gst.evidence.application.export.ExportResult
import com.dynokore.gst.evidence.application.export.ExportScope
import com.dynokore.gst.evidence.application.export.ExportState
import com.dynokore.gst.evidence.application.export.ExportTenantEvidence
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** INV-AC-22949: the handler builds the request from the payload and the envelope id; poison writes nothing. */
class OffboardingExportRequestedHandlerTest {
    private val tenant = UUID.randomUUID()
    private val registered = mutableListOf<ExportRequest>()
    private val published = mutableListOf<ExportResult>()

    private val jobs =
        object : ExportJobs {
            override fun find(
                tenant: TenantId,
                offboardingRef: String,
            ): ExportJob? = null

            override fun register(request: ExportRequest): ExportJob {
                registered += request
                return ExportJob(request.tenant, request.offboardingRef, request.exportRef, ExportState.REQUESTED)
            }

            override fun markReady(
                job: ExportJob,
                manifestRef: String,
                itemCount: Int,
                contents: List<String>,
            ) = job.copy(state = ExportState.READY)

            override fun markFailed(
                job: ExportJob,
                reason: String,
            ) = job.copy(state = ExportState.FAILED, failureReason = reason)
        }

    // no source is bound, so the run ends FAILED naming the classes: the handler only has to hand the request over
    private val handler =
        OffboardingExportRequestedHandler(
            ExportTenantEvidence(
                ExportClassSources(emptyMap()),
                jobs,
                { _, _, _ -> "ref" },
                { true },
                { published += it },
                Clock.systemUTC(),
            ),
        )

    private fun envelope(
        data: String,
        id: String = "ev-1",
    ) = Envelope(
        id = EventId(id),
        source = "/gst/tenancy-svc",
        type = "com.dynokore.gst.tenancy.OffboardingExportRequested.v1",
        subject = "OFF-1",
        time = Instant.parse("2026-11-15T08:30:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("ENT-1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "tenancy-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun payload(
        drop: String? = null,
        tenantRef: String = tenant.toString(),
        scope: String = "TENANT",
    ): String {
        val members =
            linkedMapOf(
                "tenantRef" to tenantRef,
                "offboardingRef" to "OFF-1",
                "exportRef" to "EXP-1",
                "scope" to scope,
                "requestedAt" to "2026-11-15T08:00:00Z",
            )
        drop?.let { members.remove(it) }
        return members.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
    }

    @Test
    fun `INV-AC-22949 a request registers the job with the payload members and the envelope id, and reports APPLIED`() {
        handler.handle(envelope(payload(), "ev-77")) shouldBe HandleOutcome.APPLIED
        val request = registered.single()
        request.tenant shouldBe TenantId(tenant)
        request.offboardingRef shouldBe "OFF-1"
        request.exportRef shouldBe "EXP-1"
        request.scope shouldBe ExportScope.TENANT
        request.requestedEventId shouldBe "ev-77"
        published.single().state shouldBe ExportState.FAILED
    }

    @Test
    fun `INV-AC-22949 a payload without offboardingRef is a contract violation and registers nothing`() {
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(drop = "offboardingRef"))) }
        registered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 a payload without tenantRef or exportRef, or with an unknown scope, is refused`() {
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(drop = "tenantRef"))) }
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(drop = "exportRef"))) }
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(scope = "ALL"))) }
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(drop = "scope"))) }
        shouldThrow<ContractViolation> { handler.handle(envelope("not json")) }
        registered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 a tenantRef that differs from the envelope tenant is refused`() {
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(tenantRef = UUID.randomUUID().toString()))) }
        registered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 a scope ENTITY request without entityRef is refused`() {
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(scope = "ENTITY"))) }
        registered shouldBe emptyList()
    }
}
