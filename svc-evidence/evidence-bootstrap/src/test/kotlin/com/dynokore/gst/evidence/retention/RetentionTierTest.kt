package com.dynokore.gst.evidence.retention

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.evidence.domain.retention.RetentionTier
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * INV-Q-6109: the tiers are the fields of the LONGER record of VP-43 in the generated gst-catalogue. Both ways: a
 * tier without a field, and a field without a tier, fail. The probes of the task (a fourth constant; the look-alike
 * value `structuredSubmission`) each break one side.
 */
class RetentionTierTest {
    private fun catalogueFields(): Set<String> =
        VariationPoint.entries
            .single { it.id == "VP-43" }
            .optionSpecs
            .orEmpty()
            .single { it.key == "LONGER" }
            .typedValue
            .let { checkNotNull(it) { "LONGER of VP-43 carries no typed value" } }
            .fields
            .keys

    @Test
    fun `the property values of RetentionTier equal the field names of the LONGER record of VP-43`() {
        val properties = RetentionTier.entries.map { it.property }
        properties.toSet() shouldBe catalogueFields()
        properties.size shouldBe catalogueFields().size
    }

    @Test
    fun `RetentionTier has exactly the three constants with the real property names`() {
        RetentionTier.entries.associate { it.name to it.property } shouldBe
            mapOf(
                "STRUCTURED_SUBMISSIONS" to "structuredSubmissions",
                "ACKNOWLEDGEMENTS_AND_TRACES" to "acknowledgementsAndTraces",
                "SOURCE_DOCUMENT_REFERENCES" to "sourceDocumentReferences",
            )
    }
}
