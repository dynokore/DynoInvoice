package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.period.DueDateCause
import com.dynokore.gst.tenancy.application.period.PeriodDueDateChange
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/** INV-AC-20668: the PeriodDueDateChanged payload carries dueDateVersion as a JSON integer next to the five members. */
class PeriodEventsOutboxPayloadTest {
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
    private val outbox = PeriodEventsOutbox(RecordingOutbox(), mapper, tenants)
    private val change =
        PeriodDueDateChange(
            "ENT-A",
            "2026-Q1",
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 4),
            DueDateCause.Extension("IRAS-1"),
        )
    private val contractMembers = setOf("entityRef", "period", "oldFilingDueDate", "newFilingDueDate", "cause")

    @Test
    fun `INV-AC-20668 dueDatePayload carries dueDateVersion as an integer node with the given value`() {
        val node = outbox.dueDatePayload(change, 7L)
        node.get("dueDateVersion").isIntegralNumber shouldBe true
        node.get("dueDateVersion").asLong() shouldBe 7L
        node.toString().contains("\"dueDateVersion\":7") shouldBe true
    }

    @Test
    fun `INV-AC-20668 the member names equal the five contract members plus dueDateVersion`() {
        val names = outbox.dueDatePayload(change, 1L).propertyNames().toSet()
        names shouldBe contractMembers + "dueDateVersion"
        // the look-alike member name is not the same set
        names shouldNotBe contractMembers + "dueDateVersions"
    }

    @Test
    fun `INV-AC-20668 dueDateChanged appends the version in the published event data`() {
        val recording = RecordingOutbox()
        PeriodEventsOutbox(recording, mapper, tenants).dueDateChanged(change, 3L)
        mapper.readTree(recording.events.single().dataJson).get("dueDateVersion").asLong() shouldBe 3L
    }
}
