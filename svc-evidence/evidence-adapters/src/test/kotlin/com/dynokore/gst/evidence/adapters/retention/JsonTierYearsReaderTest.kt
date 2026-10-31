package com.dynokore.gst.evidence.adapters.retention

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JsonTierYearsReaderTest {
    private val reader = JsonTierYearsReader()
    private val record = """{"structuredSubmissions":7,"acknowledgementsAndTraces":9,"sourceDocumentReferences":5}"""

    @Test
    fun `the years of a tier are the whole number of its property`() {
        reader.years(record, "structuredSubmissions") shouldBe 7
        reader.years(record, "acknowledgementsAndTraces") shouldBe 9
    }

    @Test
    fun `a missing, look-alike, fractional or non-numeric field fails closed`() {
        listOf(
            "structuredSubmission",
            "StructuredSubmissions",
            "other",
        ).forEach { shouldThrow<IllegalStateException> { reader.years(record, it) } }
        shouldThrow<IllegalStateException> { reader.years("""{"a":7.5}""", "a") }
        shouldThrow<IllegalStateException> { reader.years("""{"a":"7"}""", "a") }
        shouldThrow<IllegalStateException> { reader.years("""{"a":99999999999}""", "a") }
    }
}
