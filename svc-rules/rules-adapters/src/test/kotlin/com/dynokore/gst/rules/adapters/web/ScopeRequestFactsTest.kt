package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.scope.ProfileDates
import com.dynokore.gst.rules.contract.model.EvaluateDocumentRequest
import com.dynokore.gst.rules.domain.coding.LineCoding
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.gst.rules.domain.scope.ApportionmentMode
import com.dynokore.gst.rules.domain.scope.ExemptElection
import com.dynokore.gst.rules.domain.scope.PartialExemptionFacts
import com.dynokore.gst.rules.domain.scope.ScopeOutcome
import com.dynokore.gst.rules.domain.scope.ScopeVerdict
import com.dynokore.gst.rules.domain.scope.TraceEntry
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The assembly of the scope facts from the request and the replica, and of the answer from the outcome. */
class ScopeRequestFactsTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val date = TransactionDate(LocalDate.of(2026, 6, 1))
    private val recorded = PartialExemptionFacts(true, ExemptElection(setOf("TX-N33"), "certain"))

    private fun request(
        code: String = "TX-N33 ",
        mode: EvaluateDocumentRequest.ApportionmentMode? = null,
        mixed: EvaluateDocumentRequest.MixedUse? = null,
    ) = EvaluateDocumentRequest(
        entityRef = "E1",
        transactionDate = date.value,
        internalTaxCode = code,
        apportionmentMode = mode,
        mixedUse = mixed,
    )

    private fun facts(r: EvaluateDocumentRequest) =
        ScopeRequestFacts.factsOf(
            r,
            tenant,
            date,
            LineCoding.Coded("TX-N33"),
            ProfileDates(null, null),
            recorded,
            false,
        )

    @Test
    fun `INV-AC-18741 the four request values are copied unchanged and the trailing space stays`() {
        val f =
            facts(
                request(
                    mode = EvaluateDocumentRequest.ApportionmentMode.CLAIMABLE_PORTION,
                    mixed = EvaluateDocumentRequest.MixedUse.ENTIRE_INVOICE,
                ),
            )
        f.internalTaxCode shouldBe "TX-N33 "
        f.exemption.apportionmentMode shouldBe ApportionmentMode.CLAIMABLE_PORTION
        f.exemption.mixedUse shouldBe ApportionmentMode.ENTIRE_INVOICE
        f.exemption.partiallyExempt shouldBe true
        f.exemption.election shouldBe recorded.election
    }

    @Test
    fun `INV-AC-18741 an absent mode or mixed use gives null`() {
        val f = facts(request())
        f.exemption.apportionmentMode shouldBe null
        f.exemption.mixedUse shouldBe null
    }

    @Test
    fun `INV-AC-18741 the answer code is the apportioned code when present and the mapped code otherwise`() {
        val apportioned = ScopeOutcome(ScopeVerdict.IN_SCOPE, false, emptyList(), "TX")
        ScopeRequestFacts.categoryCodeOf(apportioned, "TX-RE") shouldBe "TX"
        ScopeRequestFacts.categoryCodeOf(ScopeOutcome(ScopeVerdict.IN_SCOPE, false), "TX-RE") shouldBe "TX-RE"
    }

    @Test
    fun `INV-AC-18741 scopeRules lists the trace entries in order and is absent with an empty trace`() {
        val trace = listOf(TraceEntry("A", mapOf("k" to "v")), TraceEntry("B", mapOf("x" to "y")))
        val outcome = ScopeOutcome(ScopeVerdict.OUT_OF_SCOPE, false, trace)
        val withBase = ScopeRequestFacts.traceWith(mapOf("handler" to "h"), outcome)
        withBase shouldBe
            mapOf(
                "handler" to "h",
                "scopeRules" to
                    listOf(
                        mapOf("rule" to "A", "inputs" to mapOf("k" to "v")),
                        mapOf("rule" to "B", "inputs" to mapOf("x" to "y")),
                    ),
            )
        ScopeRequestFacts.traceWith(null, outcome)?.keys shouldBe setOf("scopeRules")
        val empty = ScopeOutcome(ScopeVerdict.IN_SCOPE, false)
        ScopeRequestFacts.traceWith(mapOf("handler" to "h"), empty) shouldBe mapOf("handler" to "h")
        ScopeRequestFacts.traceWith(null, empty) shouldBe null
    }
}
