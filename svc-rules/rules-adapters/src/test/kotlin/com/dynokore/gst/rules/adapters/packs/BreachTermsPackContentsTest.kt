package com.dynokore.gst.rules.adapters.packs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The BREACH_TERMS contents decoder and its import check (INV-REQ-1385). The hours are test data, not the terms. */
class BreachTermsPackContentsTest {
    private fun json(
        a: String? = "72",
        n: String? = "24",
        r: String? = "\"Act s 26D\"",
    ) = listOfNotNull(
        a?.let { "\"assessmentWithinHours\":$it" },
        n?.let { "\"notificationWithinHours\":$it" },
        r?.let { "\"sourceReference\":$it" },
    ).joinToString(",", "{", "}")

    private fun refusal(body: String) = PackContentsChecks().refusal("BREACH_TERMS", body.toByteArray())

    @Test
    fun `INV-AC-24937 complete contents decode to the two terms and the reference`() {
        BreachTermsPackContents.decode(json().toByteArray()) shouldBe BreachTerms(72, 24, "Act s 26D")
        refusal(json()) shouldBe null
    }

    @Test
    fun `INV-AC-24937 each missing member is refused naming it`() {
        listOf(
            json(a = null) to "assessmentWithinHours",
            json(n = null) to "notificationWithinHours",
            json(r = null) to "sourceReference",
        ).forEach { (body, member) ->
            refusal(body) shouldBe "BREACH_TERMS contents: $.$member is required"
            shouldThrow<PackContentsFailure> { BreachTermsPackContents.decode(body.toByteArray()) }.field shouldBe
                "$.$member"
        }
    }

    @Test
    fun `INV-AC-24937 a term of 0 or less is refused naming the member`() {
        listOf("0", "-1").forEach { v ->
            refusal(json(a = v)) shouldBe "BREACH_TERMS contents: $.assessmentWithinHours must be 1 or more: $v"
            refusal(json(n = v)) shouldBe "BREACH_TERMS contents: $.notificationWithinHours must be 1 or more: $v"
        }
        BreachTermsPackContents.decode(json(a = "1", n = "1").toByteArray()) shouldBe BreachTerms(1, 1, "Act s 26D")
    }

    @Test
    fun `INV-AC-24937 a non-integer term is refused naming the member`() {
        refusal(json(a = "24.5")) shouldBe
            "BREACH_TERMS contents: $.assessmentWithinHours must be a whole number of hours"
        refusal(json(n = "\"24\"")) shouldBe
            "BREACH_TERMS contents: $.notificationWithinHours must be a whole number of hours"
    }

    @Test
    fun `INV-AC-24937 an empty sourceReference is refused`() {
        refusal(json(r = "\"\"")) shouldContain "$.sourceReference"
    }

    @Test
    fun `INV-AC-24937 an extra member is accepted`() {
        val body = json().dropLast(1) + ",\"extra\":1}"
        BreachTermsPackContents.decode(body.toByteArray()) shouldBe BreachTerms(72, 24, "Act s 26D")
        refusal(body) shouldBe null
    }

    @Test
    fun `INV-AC-24937 a look-alike pack type is not checked`() {
        listOf("BREACH_TERM", "breach_terms", "BREACH_TERMS ").forEach {
            PackContentsChecks().refusal(it, "{}".toByteArray()) shouldBe null
        }
    }
}
