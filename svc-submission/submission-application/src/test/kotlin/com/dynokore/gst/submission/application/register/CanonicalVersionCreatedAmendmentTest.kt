package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.application.correction.CreditNoteCorrectionHandler
import com.dynokore.gst.submission.application.correction.CreditNoteTransitions
import com.dynokore.gst.submission.application.correction.NetDerivationRequestPort
import com.dynokore.gst.submission.application.correction.NetFinding
import com.dynokore.gst.submission.application.correction.NetFindingKind
import com.dynokore.gst.submission.application.correction.NetRefusedMarks
import com.dynokore.gst.submission.application.correction.NettedAmendmentGuard
import com.dynokore.gst.submission.application.correction.NettingDeclarationRecorder
import com.dynokore.gst.submission.application.correction.OriginalRef
import com.dynokore.gst.submission.application.correction.OriginalVersionSource
import com.dynokore.gst.submission.domain.correction.CorrectionReason
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The routing of credit note versions through the netted amendment guard (INV-AC-25529), over fakes only. */
class CanonicalVersionCreatedAmendmentTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000025529"))
    private val doc = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    private val original = "original-O"

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class Store : RegisterEntryStore {
        val entries = mutableListOf<RegisterEntry>()

        override fun upsert(
            tenant: TenantId,
            entry: RegisterEntry,
        ): UpsertResult {
            entries += entry
            return UpsertResult.INSERTED
        }
    }

    private class Run(
        val handler: CanonicalVersionCreatedHandler,
        val store: Store,
        val originalLookups: MutableList<String>,
        val findings: MutableList<NetFinding>,
    )

    /** A real guard over fake ports: [amends] decides whether version 2 amends a netted, transmitted credit note. */
    private fun run(
        kind: RowKind,
        amends: Boolean,
    ): Run {
        val lookups = mutableListOf<String>()
        val findings = mutableListOf<NetFinding>()
        val originals =
            object : OriginalVersionSource {
                override fun versionsOf(creditNoteDocUuid: String): List<LifecycleState> =
                    if (amends) listOf(LifecycleState.ACKNOWLEDGED) else emptyList()

                override fun originalOf(creditNoteDocUuid: String): OriginalRef? {
                    lookups += creditNoteDocUuid
                    return if (amends) OriginalRef(original, 3) else null
                }
            }
        val transitions =
            object : CreditNoteTransitions {
                override fun notSubmittable(
                    creditNoteDocUuid: String,
                    reason: CorrectionReason,
                ) = Unit

                override fun nettedBySystem(originalDocUuid: String): List<String> =
                    if (amends) listOf(doc) else emptyList()

                override fun lift(creditNoteDocUuid: String) = Unit
            }
        val guard = NettedAmendmentGuard(originals, transitions, { 1 }, { findings.add(it) })
        val corrections = corrections(originals, transitions)
        val store = Store()
        val data =
            CanonicalVersionCreatedData(
                doc,
                2,
                LocalDate.of(2026, 3, 20),
                "2026-Q1",
                "SUBMITTABLE",
                payload(),
            )
        val handler =
            CanonicalVersionCreatedHandler(
                tenants,
                { data },
                { _, _, _ -> FilingDeadlineInputs(LocalDate.of(2026, 4, 30), emptyList()) },
                { kind },
                store,
                corrections,
                guard,
            )
        return Run(handler, store, lookups, findings)
    }

    private fun transitions(amends: Boolean) =
        object : CreditNoteTransitions {
            override fun notSubmittable(
                creditNoteDocUuid: String,
                reason: CorrectionReason,
            ) = Unit

            override fun nettedBySystem(originalDocUuid: String): List<String> =
                if (amends) listOf(doc) else emptyList()

            override fun lift(creditNoteDocUuid: String) = Unit
        }

    private fun corrections(
        originals: OriginalVersionSource,
        transitions: CreditNoteTransitions,
    ) = CreditNoteCorrectionHandler(
        originals,
        { false },
        transitions,
        NetDerivationRequestPort { _, _, _ -> },
        object : NetRefusedMarks {
            override fun store(creditNoteDocUuid: String) = Unit

            override fun isRefused(creditNoteDocUuid: String) = false
        },
        NettingDeclarationRecorder { _, _ -> },
        { true },
    )

    private fun payload(): Map<String, Any?> {
        val party = { n: String ->
            mapOf("identifierScheme" to "SG_UEN", "identifierValue" to "201912345A", "name" to n)
        }
        return mapOf(
            "canonicalDocument" to
                mapOf(
                    "documentNumber" to mapOf("value" to "CN-1"),
                    "documentTypeCode" to mapOf("value" to "381"),
                    "currency" to mapOf("value" to "SGD"),
                    "parties" to mapOf("value" to mapOf("supplier" to party("SUP"), "buyer" to party("BUY"))),
                    "totals" to mapOf("value" to mapOf("totalExcludingTax" to "100.00", "totalTax" to "9.00")),
                    "lines" to mapOf("value" to listOf(mapOf("taxCode" to "SR"))),
                ),
            "routingFacts" to mapOf("direction" to "PURCHASE"),
        )
    }

    private fun envelope() =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/canonical-svc",
            type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
            subject = doc,
            time = Instant.parse("2026-10-19T08:00:00Z"),
            tenant = tenant,
            entity = EntityRef("E-001"),
            docUuid = DocUuid(UUID.fromString(doc)),
            transactionDate = LocalDate.parse("2026-03-20"),
            docSequence = 1,
            instanceId = "canonical-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    @Test
    fun `INV-AC-25529 a refused credit note version is not upserted and the outcome is IGNORED`() {
        val r = run(RowKind.CREDIT_NOTE, amends = true)
        r.handler.handle(envelope()) shouldBe HandleOutcome.IGNORED
        r.store.entries.size shouldBe 0
        r.findings shouldBe listOf(NetFinding(NetFindingKind.AMENDED_AFTER_TRANSMISSION, doc, 2, original))
    }

    @Test
    fun `INV-AC-25529 a proceeding credit note version upserts exactly once as before`() {
        val r = run(RowKind.CREDIT_NOTE, amends = false)
        r.handler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        r.store.entries.size shouldBe 1
        r.store.entries
            .single()
            .sourceVersion shouldBe 2
        r.originalLookups.isNotEmpty() shouldBe true
        r.findings shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25529 a document of another kind never reaches the guard and upserts once`() {
        val r = run(RowKind.INVOICE, amends = true)
        r.handler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        r.store.entries.size shouldBe 1
        r.findings shouldBe emptyList()
    }
}
