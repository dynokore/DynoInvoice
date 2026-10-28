package com.dynokore.gst.submission.domain.scope

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class ScopeVerdictRoutingTest {
    private val schemas = listOf("RecordDocumentResponse", "DocumentSummary", "DocumentVersion")
    private val constants = setOf(SUBMITTABLE, NOT_SUBMITTABLE, PRE_OBLIGATION)

    @Test
    fun `INV-AC-10456 the three contract strings give their results`() {
        ScopeVerdictRouting.decide("SUBMITTABLE") shouldBe ScopeRoute.Submittable
        ScopeVerdictRouting.decide("NOT_SUBMITTABLE") shouldBe
            ScopeRoute.NotSubmittable(NotSubmittableRouteReason.OUT_OF_SCOPE_OR_EXCLUDED)
        ScopeVerdictRouting.decide("PRE_OBLIGATION") shouldBe
            ScopeRoute.NotSubmittable(NotSubmittableRouteReason.PRE_OBLIGATION)
    }

    @ParameterizedTest(name = "INV-AC-10456 [{0}] is Unrecognised with the string unchanged")
    @ValueSource(strings = ["pre_obligation", "PRE_OBLIGATION ", ""])
    fun `INV-AC-10456 look-alike is Unrecognised with the string unchanged`(verdict: String) {
        ScopeVerdictRouting.decide(verdict) shouldBe ScopeRoute.Unrecognised(verdict)
    }

    @Test
    fun `INV-AC-10456 decide has exactly one parameter of type String`() {
        val methods = ScopeVerdictRouting::class.java.declaredMethods.filter { it.name == "decide" }
        methods.size shouldBe 1
        methods.single().parameterTypes.toList() shouldBe listOf(String::class.java)
    }

    @Test
    fun `INV-AC-10456 the route reasons are exactly OUT_OF_SCOPE_OR_EXCLUDED then PRE_OBLIGATION`() {
        NotSubmittableRouteReason.entries.map { it.name } shouldBe listOf("OUT_OF_SCOPE_OR_EXCLUDED", "PRE_OBLIGATION")
    }

    @Test
    fun `INV-AC-2103 PRE_OBLIGATION gives NotSubmittable PRE_OBLIGATION and not Submittable`() {
        val first = ScopeVerdictRouting.decide("PRE_OBLIGATION")
        first shouldBe ScopeRoute.NotSubmittable(NotSubmittableRouteReason.PRE_OBLIGATION)
        first shouldNotBe ScopeRoute.Submittable
        ScopeVerdictRouting.decide("PRE_OBLIGATION") shouldBe first
    }

    @Test
    fun `INV-AC-2103 NOT_SUBMITTABLE gives NotSubmittable OUT_OF_SCOPE_OR_EXCLUDED`() {
        ScopeVerdictRouting.decide("NOT_SUBMITTABLE") shouldBe
            ScopeRoute.NotSubmittable(NotSubmittableRouteReason.OUT_OF_SCOPE_OR_EXCLUDED)
    }

    @Test
    fun `INV-AC-2103 SUBMITTABLE gives Submittable`() {
        ScopeVerdictRouting.decide("SUBMITTABLE") shouldBe ScopeRoute.Submittable
    }

    @Test
    fun `INV-AC-2103 the constants equal their own names`() {
        listOf(SUBMITTABLE, NOT_SUBMITTABLE, PRE_OBLIGATION) shouldBe
            listOf("SUBMITTABLE", "NOT_SUBMITTABLE", "PRE_OBLIGATION")
    }

    @Test
    fun `INV-AC-2103 only Submittable may lead to a queue entry`() {
        val inputs = listOf("SUBMITTABLE", "NOT_SUBMITTABLE", "PRE_OBLIGATION", "", "pre_obligation", "X")
        inputs.filter { ScopeVerdictRouting.decide(it) is ScopeRoute.Submittable } shouldBe listOf("SUBMITTABLE")
    }

    @ParameterizedTest(name = "INV-AC-2103 look-alike [{0}] is Unrecognised")
    @ValueSource(
        strings = [
            "", " ", "pre_obligation", "PRE-OBLIGATION", "PRE_OBLIGATION ", " SUBMITTABLE", "SUBMITTABLE\n",
            "NOT_SUBMITTABLE2", "Submittable", "IN_SCOPE", "PRE_OBLIGATIONS",
        ],
    )
    fun `INV-AC-2103 look-alike is Unrecognised`(verdict: String) {
        ScopeVerdictRouting.decide(verdict) shouldBe ScopeRoute.Unrecognised(verdict)
    }

    /** The members of the `scopeVerdict` enum of [schema]; fails closed when the schema or its enum is missing. */
    private fun scopeVerdictEnum(
        text: String,
        schema: String,
    ): Set<String> {
        val start = text.indexOf("\n    $schema:\n")
        check(start >= 0) { "schema $schema not found" }
        val body =
            text
                .substring(start + 1)
                .lines()
                .drop(1)
                .takeWhile { it.isBlank() || it.startsWith("     ") }
        val at = body.indexOf("        scopeVerdict:")
        check(at >= 0) { "scopeVerdict not found in $schema" }
        val line =
            body
                .drop(at + 1)
                .takeWhile { it.startsWith("          ") }
                .map { it.trim() }
                .firstOrNull { it.startsWith("enum:") }
        checkNotNull(line) { "scopeVerdict enum not found in $schema" }
        val match =
            checkNotNull(
                Regex("^enum:\\s*\\[([^\\]]*)]\\s*$").find(line),
            ) { "scopeVerdict enum of $schema is not an inline list" }
        return match.groupValues[1]
            .split(',')
            .map { it.trim() }
            .toSet()
    }

    private fun canonical(): String {
        val path = System.getProperty("gst.canonical.openapi")
        check(!path.isNullOrBlank()) { "system property gst.canonical.openapi is not set" }
        val file = File(path)
        check(file.isFile) { "canonical openapi not found at $path" }
        return file.readText()
    }

    @Test
    fun `INV-AC-2103 the constants equal the scopeVerdict enum of each canonical schema`() {
        val text = canonical()
        schemas.forEach { scopeVerdictEnum(text, it) shouldBe constants }
    }

    @Test
    fun `INV-AC-2103 a member the contract gains fails the comparison`() {
        val text =
            canonical().replaceFirst(
                "enum: [SUBMITTABLE, NOT_SUBMITTABLE, PRE_OBLIGATION]",
                "enum: [SUBMITTABLE, NOT_SUBMITTABLE, PRE_OBLIGATION, EXCLUDED]",
            )
        schemas.map { scopeVerdictEnum(text, it) == constants }.all { it } shouldBe false
    }

    @Test
    fun `INV-AC-2103 a member the component lacks fails the comparison`() {
        schemas.forEach { scopeVerdictEnum(canonical(), it) shouldNotBe (constants - PRE_OBLIGATION) }
    }

    @Test
    fun `INV-AC-2103 a missing schema or enum is an error`() {
        shouldThrow<IllegalStateException> {
            scopeVerdictEnum(canonical().replace("    DocumentSummary:", "    Other:"), "DocumentSummary")
        }
        shouldThrow<IllegalStateException> { scopeVerdictEnum("    components:\n", "DocumentVersion") }
        shouldThrow<IllegalStateException> {
            scopeVerdictEnum(canonical().replace("scopeVerdict:", "scopeVerdictX:"), "DocumentVersion")
        }
    }
}
