package com.dynokore.platform.rulesevaluation.packs

import com.dynokore.platform.rulesevaluation.scope.ScopeVerdict
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The scope sections of a SCOPE pack (INV-T-4968), moved from svc-rules ScopePackContentsTest. */
class ScopePackContentsTest {
    private val intraPassThrough =
        ""","intraGroupRule":{"treatment":"PASS_THROUGH","sourceClass":"INFERRED","sourceReference":"t"}"""

    private fun rule(
        code: String,
        verdict: String,
    ) = """{"categoryCode":"$code","lineVerdict":"$verdict","sourceClass":"PRIMARY","sourceReference":"t"}"""

    private fun scopePack(
        rules: List<String>,
        intra: String = intraPassThrough,
    ) = """{"lineScopeRules":[${rules.joinToString(",")}]$intra}""".toByteArray()

    @Test
    fun `INV-AC-19452 the decoded map equals the rows of the sample pack and the treatment is read`() {
        val data =
            ScopePackContents.decodeScopeData(
                scopePack(listOf(rule("SR", "IN_SCOPE"), rule("DS", "EXCLUDED_TXN"), rule("OS", "OUT_OF_SCOPE"))),
            )
        data.lineVerdicts shouldBe
            mapOf(
                "SR" to ScopeVerdict.IN_SCOPE,
                "DS" to ScopeVerdict.EXCLUDED_TXN,
                "OS" to ScopeVerdict.OUT_OF_SCOPE,
            )
        data.intraGroupTreatment shouldBe "PASS_THROUGH"
    }

    @Test
    fun `INV-AC-19452 a line verdict outside the three members fails the decoder`() {
        listOf("SUBMITTABLE", "NOT_SUBMITTABLE", "in_scope", "").forEach {
            shouldThrow<PackContentsFailure> {
                ScopePackContents.decodeScopeData(scopePack(listOf(rule("SR", it))))
            }.field shouldBe "$.lineScopeRules[0].lineVerdict"
        }
    }

    @Test
    fun `INV-AC-19452 a missing intraGroupRule decodes to an absent treatment`() {
        ScopePackContents
            .decodeScopeData(
                scopePack(listOf(rule("SR", "IN_SCOPE")), intra = ""),
            ).intraGroupTreatment shouldBe
            null
    }

    @Test
    fun `INV-AC-19452 a category code twice or no rules fail the decoder`() {
        shouldThrow<PackContentsFailure> {
            ScopePackContents.decodeScopeData(scopePack(listOf(rule("SR", "IN_SCOPE"), rule("SR", "OUT_OF_SCOPE"))))
        }
        shouldThrow<PackContentsFailure> { ScopePackContents.decodeScopeData(scopePack(emptyList())) }
    }
}
