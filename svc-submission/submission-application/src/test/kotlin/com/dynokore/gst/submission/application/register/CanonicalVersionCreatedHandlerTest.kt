package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.application.correction.CreditNoteCorrectionHandler
import com.dynokore.gst.submission.application.correction.CreditNoteTransitions
import com.dynokore.gst.submission.application.correction.NetDerivationRequestPort
import com.dynokore.gst.submission.application.correction.NetRefusedMarks
import com.dynokore.gst.submission.application.correction.NettingDeclarationRecorder
import com.dynokore.gst.submission.application.correction.OriginalRef
import com.dynokore.gst.submission.application.correction.OriginalVersionSource
import com.dynokore.gst.submission.domain.correction.CorrectionReason
import com.dynokore.gst.submission.domain.deadline.SubmissionDeadline
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.register.ListFactsPolicy
import com.dynokore.gst.submission.domain.register.ListFactsResult
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The register writer's handler with fake ports (INV-AC-16339). */
class CanonicalVersionCreatedHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000016"))
    private val doc = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    private val due = LocalDate.of(2026, 4, 30)

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

    private class Store(
        private val answers: ArrayDeque<UpsertResult>,
    ) : RegisterEntryStore {
        val entries = mutableListOf<RegisterEntry>()

        override fun upsert(
            tenant: TenantId,
            entry: RegisterEntry,
        ): UpsertResult {
            entries += entry
            return answers.removeFirst()
        }
    }

    private fun party(name: String) =
        mapOf("identifierScheme" to "SG_UEN", "identifierValue" to "201912345A", "name" to name)

    private fun payload(number: String? = "INV-1"): Map<String, Any?> =
        buildMap {
            put(
                "canonicalDocument",
                buildMap {
                    if (number != null) put("documentNumber", mapOf("value" to number))
                    put("documentTypeCode", mapOf("value" to "380"))
                    put("currency", mapOf("value" to "SGD"))
                    val parties = mapOf("supplier" to party("SUP"), "buyer" to party("CANARY-BUYER Pte Ltd"))
                    put("parties", mapOf("value" to parties))
                    put("totals", mapOf("value" to mapOf("totalExcludingTax" to "100.00", "totalTax" to "9.00")))
                    put("lines", mapOf("value" to listOf(mapOf("taxCode" to "SR"))))
                },
            )
            put("routingFacts", mapOf("direction" to "SALE"))
        }

    private fun data(
        verdict: String = "SUBMITTABLE",
        payload: Map<String, Any?> = payload(),
    ) = CanonicalVersionCreatedData(doc, 2, LocalDate.of(2026, 3, 20), "2026-Q1", verdict, payload)

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

    private class Fixture(
        val handler: CanonicalVersionCreatedHandler,
        val store: Store,
        val calls: MutableList<String>,
    )

    /** A correction handler over recording fake ports: every port call is appended to [calls]. */
    private fun recordingCorrections(calls: MutableList<String>) =
        CreditNoteCorrectionHandler(
            object : OriginalVersionSource {
                override fun versionsOf(creditNoteDocUuid: String): List<LifecycleState> {
                    calls += "versionsOf $creditNoteDocUuid"
                    return emptyList()
                }

                override fun originalOf(creditNoteDocUuid: String): OriginalRef? = null
            },
            { false },
            object : CreditNoteTransitions {
                override fun notSubmittable(
                    creditNoteDocUuid: String,
                    reason: CorrectionReason,
                ) {
                    calls += "notSubmittable $creditNoteDocUuid $reason"
                }

                override fun nettedBySystem(originalDocUuid: String): List<String> {
                    calls += "nettedBySystem $originalDocUuid"
                    return emptyList()
                }

                override fun lift(creditNoteDocUuid: String) {
                    calls += "lift $creditNoteDocUuid"
                }
            },
            NetDerivationRequestPort { o, b, n -> calls += "request $o $b $n" },
            object : NetRefusedMarks {
                override fun store(creditNoteDocUuid: String) {
                    calls += "mark $creditNoteDocUuid"
                }

                override fun isRefused(creditNoteDocUuid: String): Boolean = false
            },
            NettingDeclarationRecorder { d, set -> calls += "replaceDeclared $d ${set.sorted()}" },
        )

    private fun fixture(
        data: CanonicalVersionCreatedData = data(),
        inputs: FilingDeadlineInputs? = FilingDeadlineInputs(LocalDate.of(2026, 4, 30), emptyList()),
        kind: RowKind? = RowKind.INVOICE,
        answers: List<UpsertResult> = listOf(UpsertResult.INSERTED),
    ): Fixture {
        val store = Store(ArrayDeque(answers))
        val calls = mutableListOf<String>()
        val handler =
            CanonicalVersionCreatedHandler(
                tenants,
                { data },
                { _, _, _ -> inputs },
                { kind },
                store,
                recordingCorrections(calls),
            )
        return Fixture(handler, store, calls)
    }

    @Test
    fun `INV-AC-16339 a complete submittable event upserts once with the expected entry`() {
        val f = fixture()
        f.handler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        val e = f.store.entries.single()
        e.docUuid shouldBe doc
        e.entityRef shouldBe "E-001"
        e.periodRef shouldBe "2026-Q1"
        e.inScope shouldBe true
        e.kind shouldBe RowKind.INVOICE
        e.dueDate shouldBe due
        e.sourceVersion shouldBe 2
        e.facts shouldBe (ListFactsPolicy.extract(payload()) as ListFactsResult.Complete).facts
        e.facts.direction shouldBe Direction.SALE
    }

    @Test
    fun `INV-AC-20479 the entry passed to the store carries the transaction date of the event`() {
        val f = fixture()
        f.handler.handle(envelope())
        f.store.entries
            .single()
            .transactionDate shouldBe LocalDate.of(2026, 3, 20)
    }

    @Test
    fun `INV-AC-16339 the kind comes from the resolver`() {
        val f = fixture(kind = RowKind.CREDIT_NOTE)
        f.handler.handle(envelope())
        f.store.entries
            .single()
            .kind shouldBe RowKind.CREDIT_NOTE
    }

    @Test
    fun `INV-AC-16339 the due date is SubmissionDeadline of and an earlier filing date wins`() {
        val filed = LocalDate.of(2026, 4, 10)
        val f = fixture(inputs = FilingDeadlineInputs(due, listOf(filed)))
        f.handler.handle(envelope())
        f.store.entries
            .single()
            .dueDate shouldBe SubmissionDeadline.of(due, listOf(filed)).date
        f.store.entries
            .single()
            .dueDate shouldBe filed
    }

    @Test
    fun `INV-AC-16339 an incomplete payload names the fields no value and writes nothing`() {
        val f = fixture(data = data(payload = payload(number = null)))
        val ex = shouldThrow<ContractViolation> { f.handler.handle(envelope()) }
        ex.message!! shouldContain doc
        ex.message!! shouldContain "version 2"
        ex.message!! shouldContain "documentNumber"
        ex.message!! shouldNotContain "CANARY"
        ex.message!! shouldNotContain "201912345A"
        ex.message!! shouldNotContain "100.00"
        f.store.entries.size shouldBe 0
    }

    @Test
    fun `INV-AC-16339 PRE_OBLIGATION and NOT_SUBMITTABLE give inScope false`() {
        listOf("PRE_OBLIGATION", "NOT_SUBMITTABLE").forEach {
            val f = fixture(data = data(verdict = it))
            f.handler.handle(envelope())
            f.store.entries
                .single()
                .inScope shouldBe false
        }
    }

    @Test
    fun `INV-AC-16339 an unknown verdict a null kind or a second unknown value write nothing`() {
        listOf(
            fixture(data = data(verdict = "SOMETHING")),
            fixture(data = data(verdict = "submittable")),
            fixture(kind = null),
        ).forEach {
            shouldThrow<ContractViolation> { it.handler.handle(envelope()) }
            it.store.entries.size shouldBe 0
        }
    }

    @Test
    fun `INV-AC-16339 a null deadline input is an IllegalStateException and writes nothing`() {
        val f = fixture(inputs = null)
        shouldThrow<IllegalStateException> { f.handler.handle(envelope()) }
        f.store.entries.size shouldBe 0
    }

    @Test
    fun `INV-AC-16339 the same event twice gives APPLIED then IGNORED`() {
        val f = fixture(answers = listOf(UpsertResult.INSERTED, UpsertResult.UNCHANGED))
        f.handler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        f.handler.handle(envelope()) shouldBe HandleOutcome.IGNORED
        f.store.entries.size shouldBe 2
        f.store.entries[0] shouldBe f.store.entries[1]
    }

    @Test
    fun `INV-AC-24492 the entry carries the verdict, reason and preceding UUID of the event`() {
        val d0 = "0a1b2c3d-0000-4000-8000-00000000d0d0"
        val e2 =
            fixture(data = data("NOT_SUBMITTABLE").withScopeFacts("BUYER_REJECTED", d0))
        e2.handler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        val e = e2.store.entries.single()
        listOf(e.scopeVerdict, e.notSubmittableReason, e.precedingDocUuid, e.inScope) shouldBe
            listOf("NOT_SUBMITTABLE", "BUYER_REJECTED", d0, false)
        val e1 =
            fixture()
                .also { it.handler.handle(envelope()) }
                .store.entries
                .single()
        factsOf(e1) shouldBe listOf("SUBMITTABLE", null, null)
        val e3 =
            fixture(data = data("NOT_SUBMITTABLE"))
                .also { it.handler.handle(envelope()) }
                .store.entries
                .single()
        factsOf(e3) shouldBe listOf("NOT_SUBMITTABLE", null, null)
        val e4 =
            fixture(data = data("PRE_OBLIGATION"))
                .also { it.handler.handle(envelope()) }
                .store.entries
                .single()
        factsOf(e4) shouldBe listOf("PRE_OBLIGATION", null, null)
    }

    @Test
    fun `INV-AC-24492 a higher-version replay carries the new reason, an unrecognised verdict writes no row`() {
        val replay = data("NOT_SUBMITTABLE").withScopeFacts("ISSUANCE_REFUSED", null)
        val f = fixture(data = replay, answers = listOf(UpsertResult.UPDATED))
        f.handler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        f.store.entries
            .single()
            .notSubmittableReason shouldBe "ISSUANCE_REFUSED"
        val bad = fixture(data = data("MAYBE").withScopeFacts("BUYER_REJECTED", null))
        shouldThrow<ContractViolation> { bad.handler.handle(envelope()) }
        bad.store.entries.size shouldBe 0
    }

    private fun factsOf(e: RegisterEntry) = listOf(e.scopeVerdict, e.notSubmittableReason, e.precedingDocUuid)

    private fun CanonicalVersionCreatedData.withScopeFacts(
        reason: String?,
        preceding: String?,
    ) = CanonicalVersionCreatedData(
        docUuid,
        version,
        transactionDate,
        prescribedPeriodRef,
        scopeVerdict,
        payload,
        reason,
        preceding,
    )

    @Test
    fun `INV-AC-16339 UPDATED gives APPLIED`() {
        fixture(answers = listOf(UpsertResult.UPDATED)).handler.handle(envelope()) shouldBe HandleOutcome.APPLIED
    }

    private fun payloadOf(
        direction: String,
        netted: Any? = null,
    ): Map<String, Any?> =
        buildMap {
            putAll(payload())
            put("routingFacts", mapOf("direction" to direction))
            if (netted != null) put("nettedCreditNoteDocUuids", netted)
        }

    private fun dataOf(
        payload: Map<String, Any?>,
        netDerivation: NetDerivationData? = null,
    ) = CanonicalVersionCreatedData(
        doc,
        2,
        LocalDate.of(2026, 3, 20),
        "2026-Q1",
        "SUBMITTABLE",
        payload,
        netDerivation = netDerivation,
    )

    private val derivation = NetDerivationData(1, listOf(NetDerivedCreditNote("cn-1", 1)))

    private fun route(
        kind: RowKind?,
        payload: Map<String, Any?>,
        netDerivation: NetDerivationData? = null,
        answer: UpsertResult = UpsertResult.INSERTED,
    ): Pair<Fixture, HandleOutcome> {
        val f = fixture(data = dataOf(payload, netDerivation), kind = kind, answers = listOf(answer))
        return f to f.handler.handle(envelope())
    }

    @Test
    fun `INV-AC-25468 a purchase credit note calls decide once with its docUuid`() {
        val (f, outcome) = route(RowKind.CREDIT_NOTE, payloadOf("PURCHASE"))
        f.calls shouldBe listOf("versionsOf $doc")
        outcome shouldBe HandleOutcome.APPLIED
    }

    @Test
    fun `INV-AC-25468 a sales credit note reaches no method of the correction handler`() {
        val (f, outcome) = route(RowKind.CREDIT_NOTE, payloadOf("SALE"))
        f.calls shouldBe emptyList()
        outcome shouldBe HandleOutcome.APPLIED
    }

    @Test
    fun `INV-AC-25468 an invoice with netDerivation calls onOriginalVersion with it and an empty declared set`() {
        val (f, outcome) = route(RowKind.INVOICE, payloadOf("PURCHASE"), derivation)
        f.calls shouldBe listOf("nettedBySystem $doc", "versionsOf cn-1", "notSubmittable cn-1 NETTED_BY_SYSTEM")
        outcome shouldBe HandleOutcome.APPLIED
    }

    @Test
    fun `INV-AC-25468 an invoice without netDerivation hands the listed credit note docUuids as declared keys`() {
        val (f, outcome) = route(RowKind.INVOICE, payloadOf("PURCHASE", listOf("cn-b", "cn-a", 7)))
        f.calls shouldBe listOf("replaceDeclared $doc [cn-a, cn-b]", "nettedBySystem $doc")
        outcome shouldBe HandleOutcome.APPLIED
    }

    @Test
    fun `INV-AC-25468 an invoice whose nettedCreditNoteDocUuids is absent or not a list declares an empty set`() {
        route(RowKind.INVOICE, payloadOf("PURCHASE")).first.calls shouldBe
            listOf("replaceDeclared $doc []", "nettedBySystem $doc")
        route(RowKind.INVOICE, payloadOf("PURCHASE", "cn-a")).first.calls shouldBe
            listOf("replaceDeclared $doc []", "nettedBySystem $doc")
    }

    @Test
    fun `INV-AC-25468 a null kind throws ContractViolation before any upsert and reaches no correction call`() {
        val f = fixture(data = dataOf(payloadOf("PURCHASE")), kind = null)
        shouldThrow<ContractViolation> { f.handler.handle(envelope()) }
        f.store.entries.size shouldBe 0
        f.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25468 an UNCHANGED upsert gives IGNORED and reaches no correction call for each routed document`() {
        listOf(
            Triple(RowKind.CREDIT_NOTE, payloadOf("PURCHASE"), null),
            Triple(RowKind.CREDIT_NOTE, payloadOf("SALE"), null),
            Triple(RowKind.INVOICE, payloadOf("PURCHASE"), derivation),
            Triple(RowKind.INVOICE, payloadOf("PURCHASE", listOf("cn-a")), null),
        ).forEach { (kind, payload, net) ->
            val (f, outcome) = route(kind, payload, net, UpsertResult.UNCHANGED)
            f.calls shouldBe emptyList()
            outcome shouldBe HandleOutcome.IGNORED
        }
    }

    @Test
    fun `INV-AC-25468 an exception of the correction handler propagates`() {
        val f = fixture(data = dataOf(payloadOf("PURCHASE")), kind = RowKind.CREDIT_NOTE)
        val failing =
            CanonicalVersionCreatedHandler(
                tenants,
                { dataOf(payloadOf("PURCHASE")) },
                { _, _, _ -> FilingDeadlineInputs(LocalDate.of(2026, 4, 30), emptyList()) },
                { RowKind.CREDIT_NOTE },
                f.store,
                CreditNoteCorrectionHandler(
                    object : OriginalVersionSource {
                        override fun versionsOf(creditNoteDocUuid: String): List<LifecycleState> = error("boom")

                        override fun originalOf(creditNoteDocUuid: String): OriginalRef? = null
                    },
                    { false },
                    object : CreditNoteTransitions {
                        override fun notSubmittable(
                            creditNoteDocUuid: String,
                            reason: CorrectionReason,
                        ) = Unit

                        override fun nettedBySystem(originalDocUuid: String): List<String> = emptyList()

                        override fun lift(creditNoteDocUuid: String) = Unit
                    },
                    NetDerivationRequestPort { _, _, _ -> },
                    object : NetRefusedMarks {
                        override fun store(creditNoteDocUuid: String) = Unit

                        override fun isRefused(creditNoteDocUuid: String): Boolean = false
                    },
                    NettingDeclarationRecorder { _, _ -> },
                ),
            )
        shouldThrow<IllegalStateException> { failing.handle(envelope()) }.message shouldBe "boom"
    }
}
