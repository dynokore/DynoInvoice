package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.domain.deadline.SubmissionDeadline
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.metrics.RowKind
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
    )

    private fun fixture(
        data: CanonicalVersionCreatedData = data(),
        inputs: FilingDeadlineInputs? = FilingDeadlineInputs(LocalDate.of(2026, 4, 30), emptyList()),
        kind: RowKind? = RowKind.INVOICE,
        answers: List<UpsertResult> = listOf(UpsertResult.INSERTED),
    ): Fixture {
        val store = Store(ArrayDeque(answers))
        val handler =
            CanonicalVersionCreatedHandler(
                tenants,
                { data },
                { _, _, _ -> inputs },
                { kind },
                store,
            )
        return Fixture(handler, store)
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
    fun `INV-AC-16339 UPDATED gives APPLIED`() {
        fixture(answers = listOf(UpsertResult.UPDATED)).handler.handle(envelope()) shouldBe HandleOutcome.APPLIED
    }
}
