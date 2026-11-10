package com.dynokore.platform.rulesevaluation.scope

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ScopeVerdictTest {
    @Test
    fun `INV-AC-19452 members equal the contract enum in order`() {
        ScopeVerdict.entries.map { it.name } shouldBe
            listOf("SUBMITTABLE", "NOT_SUBMITTABLE", "PRE_OBLIGATION", "IN_SCOPE", "OUT_OF_SCOPE", "EXCLUDED_TXN")
    }
}
