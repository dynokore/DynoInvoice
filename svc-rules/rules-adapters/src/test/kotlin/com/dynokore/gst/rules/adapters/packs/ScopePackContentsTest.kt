package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.domain.classification.DocumentFunction
import com.dynokore.gst.rules.domain.classification.SignOfTotal
import com.dynokore.gst.rules.domain.scope.ScopeVerdict
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The classification table of a SCOPE pack (INV-REQ-658) as the decoder reads it. All values are invented. */
class ScopePackContentsTest {
    private fun row(
        kind: String = "INVOICE",
        sign: String = "NEGATIVE",
        result: String = "ADJUSTMENT",
        extra: String = ""","documentTypeCode":"381"""",
    ) = """{"upstreamFunction":"BILL_FOR_PAYMENT","upstreamKind":"$kind","signOfTotal":"$sign",""" +
        """"documentFunction":"$result","signNormalised":true,"sourceClass":"PRIMARY","sourceReference":"t"$extra}"""

    private fun pack(vararg rows: String) = """{"classificationTable":[${rows.joinToString(",")}],"other":{}}"""

    private fun failure(json: String) =
        shouldThrow<PackContentsFailure> { ScopePackContents.decode(json.toByteArray()) }

    @Test
    fun `a valid table decodes and keeps the cancellation and provisional marks`() {
        val rows =
            ScopePackContents.decode(
                pack(
                    row(),
                    row("CANCEL", "POSITIVE", extra = ""","documentTypeCode":"381","cancellation":true"""),
                    row("PF", "POSITIVE", "NOT_A_BILL", ""),
                    row(
                        "DN",
                        "POSITIVE",
                        "ADJUSTMENT",
                        ""","documentTypeCode":"380","ruleStatus":"PROVISIONAL","provisionalReference":"Q-10"""",
                    ),
                ).toByteArray(),
            )
        rows.size shouldBe 4
        rows[0].documentTypeCode shouldBe "381"
        rows[0].signOfTotal shouldBe SignOfTotal.NEGATIVE
        rows[1].cancellation shouldBe true
        rows[2].documentFunction shouldBe DocumentFunction.NOT_A_BILL
        rows[3].provisional shouldBe true
    }

    @Test
    fun `an invalid table is a failure that names the field`() {
        failure(pack(row(), row())).message shouldContain "twice"
        failure(pack(row(result = "NOT_A_BILL"))).message shouldContain "documentTypeCode"
        failure(pack(row(extra = ""))).message shouldContain "documentTypeCode"
        failure(pack(row(extra = ""","documentTypeCode":"380","cancellation":true"""))).message shouldContain
            "cancellation"
        failure(pack(row(extra = ""","documentTypeCode":"381","ruleStatus":"PROVISIONAL""""))).message shouldContain
            "provisionalReference"
        failure("""{"classificationTable":[]}""").message shouldContain "fewer"
    }

    // ---- the scope data of INV-T-4968: lineScopeRules and intraGroupRule ----

    private fun rule(
        code: String,
        verdict: String,
    ) = """{"categoryCode":"$code","lineVerdict":"$verdict","sourceClass":"PRIMARY","sourceReference":"t"}"""

    private fun scopePack(
        rules: List<String>,
        intra: String = ""","intraGroupRule":{"treatment":"PASS_THROUGH","sourceClass":"INFERRED","sourceReference":"t"}""",
    ) = """{"lineScopeRules":[${rules.joinToString(",")}]$intra}""".toByteArray()

    @Test
    fun `the decoded map equals the rows of the sample pack and the treatment is read`() {
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
    fun `a line verdict outside the three members fails the decoder`() {
        listOf("SUBMITTABLE", "NOT_SUBMITTABLE", "in_scope", "").forEach {
            shouldThrow<PackContentsFailure> {
                ScopePackContents.decodeScopeData(scopePack(listOf(rule("SR", it))))
            }.field shouldBe "$.lineScopeRules[0].lineVerdict"
        }
    }

    @Test
    fun `a missing intraGroupRule decodes to an absent treatment`() {
        ScopePackContents.decodeScopeData(scopePack(listOf(rule("SR", "IN_SCOPE")), intra = "")).intraGroupTreatment shouldBe
            null
    }

    @Test
    fun `a category code twice or no rules fail the decoder`() {
        shouldThrow<PackContentsFailure> {
            ScopePackContents.decodeScopeData(scopePack(listOf(rule("SR", "IN_SCOPE"), rule("SR", "OUT_OF_SCOPE"))))
        }
        shouldThrow<PackContentsFailure> { ScopePackContents.decodeScopeData(scopePack(emptyList())) }
    }
}
