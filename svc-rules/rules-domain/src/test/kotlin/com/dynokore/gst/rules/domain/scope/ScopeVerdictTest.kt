package com.dynokore.gst.rules.domain.scope

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ScopeVerdictTest {
    @Test
    fun `members equal the contract enum in order`() {
        ScopeVerdict.entries.map { it.name } shouldBe
            listOf("SUBMITTABLE", "NOT_SUBMITTABLE", "PRE_OBLIGATION", "IN_SCOPE", "OUT_OF_SCOPE", "EXCLUDED_TXN")
    }
}
