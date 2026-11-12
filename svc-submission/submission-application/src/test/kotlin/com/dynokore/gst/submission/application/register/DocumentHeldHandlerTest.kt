package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.application.period.PeriodAnswer
import com.dynokore.gst.submission.domain.register.DateElection
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
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** In-memory fake of the provisional store: same rules as the port documents. */
class FakeProvisionalStore : ProvisionalEntryStore {
    val rows = mutableMapOf<String, ProvisionalEntry>()
    val registered = mutableSetOf<String>()

    override fun place(
        tenant: TenantId,
        entry: ProvisionalEntry,
    ): Placement {
        val stored = rows[entry.docUuid]
        return when {
            entry.docUuid in registered -> {
                Placement.ALREADY_REGISTERED
            }

            stored != null && stored.docsequence >= entry.docsequence -> {
                Placement.UNCHANGED
            }

            else -> {
                rows[entry.docUuid] = entry
                Placement.PLACED
            }
        }
    }

    override fun release(
        tenant: TenantId,
        docUuid: String,
        docsequence: Long,
    ): Boolean {
        val stored = rows[docUuid]
        val changes = stored != null && stored.docsequence < docsequence
        if (stored != null && changes) rows[docUuid] = stored.copy(reasonCode = null, docsequence = docsequence)
        return changes
    }
}

/** DocumentHeld handler cases H1 to H16 of INV-AC-20689 with in-memory fakes. */
class DocumentHeldHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
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
    private val stored =
        mapOf(
            "2026-06" to (LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 30)),
            "2026-07" to (LocalDate.of(2026, 7, 1)..LocalDate.of(2026, 7, 31)),
        )
    private val store = FakeProvisionalStore()
    private var election: DateElection = DateElection.Unknown
    private var next: DocumentHeldData? = null
    private val handler =
        DocumentHeldHandler(
            tenants,
            { next ?: error("no data") },
            { _, entity, date ->
                val hit = stored.entries.filter { entity == "E1" && date in it.value }
                if (hit.size == 1) PeriodAnswer.Known(hit.single().key) else PeriodAnswer.Unknown
            },
            { _, _ -> election },
            store,
            Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC),
        )

    private val base =
        DocumentHeldData(
            "U1",
            1,
            listOf("ING-016"),
            "E1",
            "SALE",
            LocalDate.of(2026, 6, 20),
            LocalDate.of(2026, 7, 2),
            true,
        )

    private fun held(change: (DocumentHeldData) -> DocumentHeldData = { it }) = change(base)

    private fun run(data: DocumentHeldData): HandleOutcome {
        next = data
        return handler.handle(
            Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/canonical-svc",
                type = "com.dynokore.gst.canonical.DocumentHeld.v1",
                subject = data.docUuid,
                time = Instant.parse("2026-10-19T08:00:00Z"),
                tenant = tenant,
                entity = EntityRef("E1"),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = LocalDate.parse("2026-07-01"),
                docSequence = data.docsequence,
                instanceId = "canonical-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = "{}",
            ),
        )
    }

    private fun expect(
        uuid: String,
        period: String,
        date: String,
        reason: String? = "ING-016",
        seq: Long = 1,
    ) {
        val e = store.rows.getValue(uuid)
        e.periodRef shouldBe period
        e.transactionDate shouldBe LocalDate.parse(date)
        e.reasonCode shouldBe reason
        e.docsequence shouldBe seq
    }

    @Test
    fun `INV-AC-20689 H1 a sale with an invoice is placed by the issuance date not the event period`() {
        run(held()) shouldBe HandleOutcome.APPLIED
        expect("U1", "2026-06", "2026-06-20")
    }

    @Test
    fun `INV-AC-20689 H2 a sale without an invoice is placed by the posting date`() {
        run(held { it.copy(invoiceIssuedOrReceived = false) }) shouldBe HandleOutcome.APPLIED
        expect("U1", "2026-07", "2026-07-02")
    }

    @Test
    fun `INV-AC-20689 H3 a purchase with election issuance is placed in June`() {
        election = DateElection.Issuance
        run(held { it.copy(direction = "PURCHASE") })
        expect("U1", "2026-06", "2026-06-20")
    }

    @Test
    fun `INV-AC-20689 H4 a purchase with election posting is placed in July`() {
        election = DateElection.Posting
        run(held { it.copy(direction = "PURCHASE") })
        expect("U1", "2026-07", "2026-07-02")
    }

    @Test
    fun `INV-AC-20689 H5 a purchase with an unknown election takes the earliest candidate date`() {
        run(held { it.copy(direction = "PURCHASE") })
        expect("U1", "2026-06", "2026-06-20")
    }

    @Test
    fun `INV-AC-20689 H6 a purchase without a supplier invoice is placed by the posting date`() {
        run(held { it.copy(direction = "PURCHASE", invoiceIssuedOrReceived = false) })
        expect("U1", "2026-07", "2026-07-02")
    }

    @Test
    fun `INV-AC-20689 H7 a sale with the case absent takes the earliest candidate date`() {
        run(held { it.copy(invoiceIssuedOrReceived = null) })
        expect("U1", "2026-06", "2026-06-20")
    }

    @Test
    fun `INV-AC-20689 H8 no posting date gives the earliest known candidate`() {
        run(held { it.copy(invoiceIssuedOrReceived = false, postingDate = null) })
        expect("U1", "2026-06", "2026-06-20")
    }

    @Test
    fun `INV-AC-20689 H9 an unknown direction is a contract violation and stores nothing`() {
        shouldThrow<ContractViolation> { run(held { it.copy(direction = "SELF") }) }
        store.rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-20689 H10 a date in no stored period is retried and stores nothing`() {
        shouldThrow<IllegalStateException> {
            run(held { it.copy(documentDate = LocalDate.of(2026, 9, 1), postingDate = null) })
        }
        store.rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-20689 H11 a payload entity that differs from the envelope entity is a contract violation`() {
        shouldThrow<ContractViolation> { run(held { it.copy(entityRef = "E2") }) }
        store.rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-20689 H12 empty hold reasons are a contract violation and store nothing`() {
        shouldThrow<ContractViolation> { run(held { it.copy(holdReasons = emptyList()) }) }
        store.rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-20689 H13 the same event delivered twice is ignored and changes no row`() {
        run(held()) shouldBe HandleOutcome.APPLIED
        run(held()) shouldBe HandleOutcome.IGNORED
        expect("U1", "2026-06", "2026-06-20")
    }

    @Test
    fun `INV-AC-20689 H14 a higher docsequence replaces the entry`() {
        run(held { it.copy(docUuid = "U14", invoiceIssuedOrReceived = false) }) shouldBe HandleOutcome.APPLIED
        expect("U14", "2026-07", "2026-07-02")
        val second = held { it.copy(docUuid = "U14", docsequence = 2, holdReasons = listOf("MDM-001")) }
        run(second) shouldBe HandleOutcome.APPLIED
        expect("U14", "2026-06", "2026-06-20", "MDM-001", 2)
    }

    @Test
    fun `INV-AC-20689 H15 a stale lower docsequence is ignored`() {
        run(held { it.copy(docUuid = "U14", invoiceIssuedOrReceived = false) })
        run(held { it.copy(docUuid = "U14", docsequence = 2, holdReasons = listOf("MDM-001")) })
        run(held { it.copy(docUuid = "U14", invoiceIssuedOrReceived = false) }) shouldBe HandleOutcome.IGNORED
        expect("U14", "2026-06", "2026-06-20", "MDM-001", 2)
    }

    @Test
    fun `INV-AC-20689 H16 a document that already has a register entry is ignored and stores nothing`() {
        store.registered += "U1"
        run(held()) shouldBe HandleOutcome.IGNORED
        store.rows.isEmpty() shouldBe true
    }
}
