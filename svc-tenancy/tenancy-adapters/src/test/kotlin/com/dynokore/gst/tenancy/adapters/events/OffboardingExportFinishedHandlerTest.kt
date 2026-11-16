package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.offboarding.CompleteOffboardingExport
import com.dynokore.gst.tenancy.application.offboarding.OffboardingExportResults
import com.dynokore.gst.tenancy.application.offboarding.OffboardingRecord
import com.dynokore.gst.tenancy.application.offboarding.StoredOffboarding
import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.OffboardingState
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** The handler of OffboardingExportFinished over an in-memory record store: the cases of the contract. */
class OffboardingExportFinishedHandlerTest {
    private val tenant = TenantId(UUID.randomUUID())

    private class MemoryResults(
        var record: OffboardingRecord?,
    ) : OffboardingExportResults {
        var applied = 0

        override fun find(offboardingRef: String) =
            record?.takeIf { it.offboardingRef == offboardingRef }?.let { StoredOffboarding(it, Instant.EPOCH) }

        override fun applyResult(
            offboardingRef: String,
            state: OffboardingState,
            manifest: ExportManifest,
            failureReason: String?,
        ): Boolean {
            val current =
                record?.takeIf {
                    it.offboardingRef == offboardingRef && it.state == OffboardingState.REQUESTED
                }
            current?.let { record = it.copy(state = state, export = manifest) }
            if (current != null) applied++
            return current != null
        }

        override fun requestedFor(
            scope: OffboardingScope,
            entityRef: String?,
        ): StoredOffboarding? = null
    }

    private val requested =
        OffboardingRecord(
            offboardingRef = "OFF-1",
            tenantRef = "T-1",
            scope = OffboardingScope.ENTITY,
            entityRef = "ENT-1",
            state = OffboardingState.REQUESTED,
            retentionCustodyHolder = RetentionCustodyHolder("Holder", "holder@example.invalid"),
            export = ExportManifest("EXP-OFF-1", ExportState.REQUESTED, emptyList()),
            idempotencyKey = "k",
        )

    private val noTenantStore =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantStore::class.java)) { _, m, _ ->
            error("${m.name} is not expected")
        } as TenantStore

    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private fun handler(results: MemoryResults) =
        OffboardingExportFinishedHandler(
            CompleteOffboardingExport(tx, results, noTenantStore),
            JsonMapper.builder().build(),
            results,
        )

    private fun event(data: String) =
        Envelope(
            id = EventId("ev-${UUID.randomUUID()}"),
            source = "/gst/evidence-svc",
            type = TenancyEventCatalogue.OFFBOARDING_EXPORT_FINISHED_TYPE,
            subject = "tenant",
            time = Instant.parse("2026-11-20T10:00:00Z"),
            tenant = tenant,
            entity = EventEntityRef.TENANT_WIDE,
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "evidence-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    private val six =
        """["TRANSMITTED_XML","ACKNOWLEDGEMENTS","DECISION_TRACES","RECONCILIATION_WORK_PAPERS",""" +
            """"SOURCE_DOCUMENT_REFERENCES","AUDIT_LOG"]"""

    private fun ready(
        tenantRef: String = "T-1",
        ref: String = "OFF-1",
        contents: String = six,
    ) = """{"tenantRef":"$tenantRef","offboardingRef":"$ref","exportRef":"EXP-$ref","state":"READY",""" +
        """"contents":$contents,"finishedAt":"2026-11-20T10:00:00Z"}"""

    @Test
    fun `INV-AC-22960 a READY envelope with six contents completes the record and the repeat is ignored`() {
        val results = MemoryResults(requested)
        handler(results).handle(event(ready())) shouldBe HandleOutcome.APPLIED
        results.record!!.state shouldBe OffboardingState.COMPLETED
        results.record!!
            .export.contents.size shouldBe 6
        handler(results).handle(event(ready())) shouldBe HandleOutcome.IGNORED
        results.applied shouldBe 1
    }

    @Test
    fun `INV-AC-22960 a FAILED envelope fails the record`() {
        val results = MemoryResults(requested)
        val data =
            """{"tenantRef":"T-1","offboardingRef":"OFF-1","exportRef":"EXP-OFF-1","state":"FAILED",""" +
                """"failureReason":"no source","finishedAt":"2026-11-20T10:00:00Z"}"""
        handler(results).handle(event(data)) shouldBe HandleOutcome.APPLIED
        results.record!!.state shouldBe OffboardingState.FAILED
    }

    @Test
    fun `INV-AC-22960 an unknown record, a missing state, an unknown state and a foreign tenantRef change nothing`() {
        val results = MemoryResults(requested)
        val noState =
            """{"tenantRef":"T-1","offboardingRef":"OFF-1","exportRef":"E","finishedAt":"2026-11-20T10:00:00Z"}"""
        val badState = ready().replace("\"READY\"", "\"DONE\"")
        for (data in listOf(
            ready(ref = "OFF-9"),
            noState,
            badState,
            ready(tenantRef = "T-2"),
        )) {
            shouldThrow<ContractViolation> { handler(results).handle(event(data)) }
        }
        results.applied shouldBe 0
        results.record!!.state shouldBe OffboardingState.REQUESTED
    }

    @Test
    fun `INV-AC-22960 a READY envelope with fewer than six contents leaves the record REQUESTED`() {
        val results = MemoryResults(requested)
        handler(results).handle(event(ready(contents = """["AUDIT_LOG"]""")))
        results.record!!.state shouldBe OffboardingState.REQUESTED
    }

    @Test
    fun `INV-AC-22960 a FAILED payload without a reason and a duplicate or unknown content are refused`() {
        val results = MemoryResults(requested)
        val noReason =
            """{"tenantRef":"T-1","offboardingRef":"OFF-1","exportRef":"E","state":"FAILED","finishedAt":"2026-11-20T10:00:00Z"}"""
        for (data in listOf(
            noReason,
            ready(contents = """["AUDIT_LOG","AUDIT_LOG"]"""),
            ready(contents = """["NOPE"]"""),
        )) {
            shouldThrow<ContractViolation> { handler(results).handle(event(data)) }
        }
        results.applied shouldBe 0
    }

    @Test
    fun `INV-AC-22960 the catalogue lists the finished event from evidence-svc and the request from tenancy-svc`() {
        val catalogue = TenancyEventCatalogue.load()
        PublishedChannels.ALL.contains(OffboardingExportRequestedOutbox.CHANNEL) shouldBe true
        catalogue.find(TenancyEventCatalogue.OFFBOARDING_EXPORT_FINISHED_TYPE) shouldBe
            CatalogueEntry(
                TenancyEventCatalogue.OFFBOARDING_EXPORT_FINISHED_TYPE,
                TenancyEventCatalogue.OFFBOARDING_EXPORT_FINISHED_TOPIC,
                setOf("evidence-svc"),
                contentTopic = false,
            )
        catalogue.find(OffboardingExportRequestedOutbox.TYPE) shouldBe
            CatalogueEntry(
                OffboardingExportRequestedOutbox.TYPE,
                "gst.tenancy.offboarding-export-requested.v1",
                setOf("tenancy-svc"),
                contentTopic = false,
            )
    }
}
