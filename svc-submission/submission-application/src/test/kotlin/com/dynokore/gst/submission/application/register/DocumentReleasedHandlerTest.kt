package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.domain.register.DocumentDirection
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

/** DocumentReleased handler cases R1 to R3 of INV-AC-20689. */
class DocumentReleasedHandlerTest {
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
    private val store = FakeProvisionalStore()
    private var next = DocumentReleasedData("U1", 1)
    private val handler = DocumentReleasedHandler(tenants, { next }, store)

    private fun row(
        uuid: String,
        seq: Long,
        reason: String,
        period: String,
        date: String,
    ) {
        store.rows[uuid] =
            ProvisionalEntry(
                uuid,
                "E1",
                period,
                DocumentDirection.SALE,
                LocalDate.parse("2026-06-20"),
                LocalDate.parse(date),
                reason,
                seq,
                Instant.parse("2026-10-19T08:00:00Z"),
            )
    }

    private fun release(
        uuid: String,
        seq: Long,
    ): HandleOutcome {
        next = DocumentReleasedData(uuid, seq)
        return handler.handle(
            Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/canonical-svc",
                type = "com.dynokore.gst.canonical.DocumentReleased.v1",
                subject = uuid,
                time = Instant.parse("2026-10-19T08:00:00Z"),
                tenant = tenant,
                entity = EntityRef("E1"),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = LocalDate.parse("2026-07-01"),
                docSequence = seq,
                instanceId = "canonical-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = "{}",
            ),
        )
    }

    @Test
    fun `INV-AC-20689 R1 a higher docsequence clears the reason and keeps period and date`() {
        row("U1", 1, "ING-016", "2026-06", "2026-06-20")
        release("U1", 3) shouldBe HandleOutcome.APPLIED
        val e = store.rows.getValue("U1")
        e.reasonCode shouldBe null
        e.docsequence shouldBe 3
        e.periodRef shouldBe "2026-06"
        e.transactionDate shouldBe LocalDate.parse("2026-06-20")
    }

    @Test
    fun `INV-AC-20689 R2 a release not above the stored docsequence is ignored`() {
        row("U14", 2, "MDM-001", "2026-06", "2026-06-20")
        release("U14", 1) shouldBe HandleOutcome.IGNORED
        store.rows.getValue("U14").reasonCode shouldBe "MDM-001"
        store.rows.getValue("U14").docsequence shouldBe 2
    }

    @Test
    fun `INV-AC-20689 R3 a release for a document with no provisional row is ignored and writes nothing`() {
        release("U99", 1) shouldBe HandleOutcome.IGNORED
        store.rows.isEmpty() shouldBe true
    }
}
