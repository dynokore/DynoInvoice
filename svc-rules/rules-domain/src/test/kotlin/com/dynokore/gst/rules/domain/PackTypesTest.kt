package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.platform.rulepackkernel.PackType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** INV-Q-7444 (A): the eight pack types in the upper snake case spelling; another spelling is not a pack type. */
class PackTypesTest {
    @Test
    fun `the pack types are exactly the eight upper snake case values`() {
        setOf(
            PackTypes.SCOPE,
            PackTypes.CODE_CATALOGUE,
            PackTypes.MDE,
            PackTypes.SCHEMATRON,
            PackTypes.SEVERITY_OVERRIDE,
            PackTypes.SCENARIO,
            PackTypes.CALENDAR,
            PackTypes.MAPPING,
        ) shouldBe
            listOf("SCOPE", "CODE_CATALOGUE", "MDE", "SCHEMATRON", "SEVERITY_OVERRIDE")
                .plus(listOf("SCENARIO", "CALENDAR", "MAPPING"))
                .map { PackType(it) }
                .toSet()
    }

    @Test
    fun `look-alike spellings are not the pack types`() {
        PackTypes.SCOPE shouldNotBe PackType("Scope")
        PackTypes.CODE_CATALOGUE shouldNotBe PackType("CODE CATALOGUE")
        PackTypes.CODE_CATALOGUE shouldNotBe PackType("code_catalogue")
        PackTypes.MAPPING shouldNotBe PackType("MAPPING ")
    }
}
