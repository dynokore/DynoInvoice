package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.period.PeriodRecordsChange
import com.dynokore.gst.tenancy.application.period.PeriodSpanRecord
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/** INV-AC-20743: every period item of the PeriodRecordsChanged payload carries the stored filingDueDate as ISO text. */
class PeriodRecordsChangedOutboxFilingDueDateTest {
    private val mapper = JsonMapper.builder().build()
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(UUID.randomUUID())

            override fun currentActor(): String = "actor"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val outbox = PeriodRecordsChangedOutbox(RecordingOutbox(), mapper, tenants)
    private val change =
        PeriodRecordsChange(
            "E-001",
            7L,
            listOf(
                PeriodSpanRecord(
                    "P1",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 3, 31),
                    LocalDate.of(2026, 4, 30),
                ),
                PeriodSpanRecord(
                    "P2",
                    LocalDate.of(2026, 4, 1),
                    LocalDate.of(2026, 6, 30),
                    LocalDate.of(2026, 8, 14),
                ),
            ),
        )

    @Test
    fun `INV-AC-20743 each period item carries filingDueDate as ISO text in the given order, others as before`() {
        val node = outbox.payload(change)
        node.get("entityRef").asString() shouldBe "E-001"
        node.get("version").asLong() shouldBe 7L
        val items = node.get("periods")
        items.size() shouldBe 2
        items.map { it.get("periodRef").asString() } shouldBe listOf("P1", "P2")
        items.map { it.get("periodStart").asString() } shouldBe listOf("2026-01-01", "2026-04-01")
        items.map { it.get("periodEnd").asString() } shouldBe listOf("2026-03-31", "2026-06-30")
        items.map { it.get("filingDueDate").asString() } shouldBe listOf("2026-04-30", "2026-08-14")
    }

    @Test
    fun `INV-AC-20743 no item lacks filingDueDate or carries null and the item members are the four plus one`() {
        val items = outbox.payload(change).get("periods")
        items.forEach {
            it.has("filingDueDate") shouldBe true
            it.get("filingDueDate").isString shouldBe true
            it.propertyNames().toList() shouldBe
                listOf("periodRef", "periodStart", "periodEnd", "filingDueDate")
        }
    }
}
