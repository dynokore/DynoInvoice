package com.dynokore.gst.canonical.adapters.document

import com.dynokore.gst.canonical.application.document.PartyIdentifier
import com.dynokore.gst.canonical.application.document.RecordDocumentCommand
import com.dynokore.gst.canonical.application.document.RecordDocumentResultBuilder
import com.dynokore.gst.canonical.application.document.ScopeAnswer
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.gst.canonical.domain.document.Direction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

class JacksonResultJsonTest {
    @Test
    fun `a held record built by the result builder has the members of the 202 example of the canonical contract`() {
        val mapper = JsonMapper.builder().build()
        val q1 = PeriodSpan("2026-Q1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
        val builder =
            RecordDocumentResultBuilder(
                periods = { _, _ -> listOf(q1) },
                elections = { _, _, _ -> null },
                scope = { _, _, _ -> ScopeAnswer.Verdict("SUBMITTABLE") },
                versions = { 1 },
                mapper = JacksonResultJson(mapper),
            )
        val command =
            RecordDocumentCommand(
                tenantId = UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                entityRef = "E1",
                sourceSystem = "erp",
                sourceKey = "k",
                sourceRevision = 1,
                transactionDate = LocalDate.of(2026, 4, 20),
                direction = Direction.SALE,
                supplier = PartyIdentifier("UEN", "S"),
                buyer = PartyIdentifier("UEN", "B"),
            )
        val tree = mapper.readTree(builder.build(command, UUID.fromString("00000000-0000-0000-0000-0000000000d1")))
        tree.propertyNames().asSequence().toList() shouldBe
            listOf("docUuid", "version", "scopeVerdict", "transactionDate", "prescribedPeriodRef", "reasons")
        tree.get("version").isNumber shouldBe true
        listOf("docUuid", "scopeVerdict", "transactionDate", "prescribedPeriodRef").forEach {
            tree.get(it).isString shouldBe true
        }
        tree.get("reasons").isArray shouldBe true
        val reason = tree.get("reasons").get(0)
        reason.propertyNames().asSequence().toList() shouldBe listOf("code", "field", "message")
        reason.get("code").asString() shouldBe "PER-009"
    }
}
