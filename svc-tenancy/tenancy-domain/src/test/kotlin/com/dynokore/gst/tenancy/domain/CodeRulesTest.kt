package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodeRulesTest {
    private fun code(text: String) = (ShortCode.parse(text) as CodeOutcome.Accepted).value

    private fun refusal(outcome: CodeOutcome<ShortCode>) = (outcome as CodeOutcome.Refused).reason

    @Test
    fun `an entity code defaults to its UEN`() {
        assertEquals(
            code("201912345K"),
            (CodeRules.entityCode(null, "201912345K", emptySet()) as CodeOutcome.Accepted).value,
        )
    }

    @Test
    fun `a UEN already used by another entity of the tenant is refused as a default`() {
        assertEquals(
            CodeRefusal.DUPLICATE,
            refusal(CodeRules.entityCode(null, "201912345K", setOf(code("201912345K")))),
        )
    }

    @Test
    fun `an entered code wins over the UEN`() {
        assertEquals(
            code("ACME"),
            (CodeRules.entityCode("ACME", "201912345K", setOf(code("201912345K"))) as CodeOutcome.Accepted).value,
        )
    }

    @Test
    fun `an entity without UEN or entered code has no code`() {
        assertEquals(CodeRefusal.NOT_ENTERED, refusal(CodeRules.entityCode(null, null, emptySet())))
    }

    @Test
    fun `a business unit code is entered and never defaulted`() {
        assertEquals(CodeRefusal.NOT_ENTERED, refusal(CodeRules.businessUnitCode(null, emptySet())))
    }

    @Test
    fun `a duplicate code is refused whichever level holds it`() {
        assertEquals(CodeRefusal.DUPLICATE, refusal(CodeRules.businessUnitCode("ORCH", setOf(code("ORCH")))))
    }

    @Test
    fun `an 11 character code is refused`() {
        assertEquals(CodeRefusal.MALFORMED, refusal(CodeRules.businessUnitCode("ABCDEFGHIJK", emptySet())))
    }

    @Test
    fun `a 10 character code is accepted`() {
        assertEquals(
            code("ABCDEFGHIJ"),
            (CodeRules.businessUnitCode("ABCDEFGHIJ", emptySet()) as CodeOutcome.Accepted).value,
        )
    }

    @Test
    fun `a lower case, empty, punctuated or look-alike code is refused`() {
        listOf("acme", "", "AC-ME", "AC ME", "ÀCME", "ACME\n", "AC\u0000").forEach {
            assertEquals(CodeRefusal.MALFORMED, refusal(CodeRules.businessUnitCode(it, emptySet())), "code <$it>")
        }
    }

    @Test
    fun `a code used in an aggregate number cannot change`() {
        assertEquals(
            CodeRefusal.USED_IN_AGGREGATE_NUMBER,
            refusal(CodeRules.change(code("ACME"), "NEWC", emptySet(), usedInAggregateNumber = true)),
        )
    }

    @Test
    fun `a code not yet used can change, and keeping the same code is not a duplicate of itself`() {
        assertEquals(
            code("NEWC"),
            (CodeRules.change(code("ACME"), "NEWC", setOf(code("ACME")), false) as CodeOutcome.Accepted).value,
        )
        assertEquals(
            code("ACME"),
            (CodeRules.change(code("ACME"), "ACME", setOf(code("ACME")), false) as CodeOutcome.Accepted).value,
        )
    }
}
