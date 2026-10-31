package com.dynokore.gst.evidence.domain.retention

/**
 * The three finite-period retention tiers evidence-svc keeps (INV-Q-6109; INV-REQ-1304 b1). Each carries as [property]
 * the name of the field of the LONGER record of VP-43 that holds the tier's years. Rule packs are retained
 * indefinitely (INV-REQ-635) and are not a tier.
 */
enum class RetentionTier(
    val property: String,
) {
    STRUCTURED_SUBMISSIONS("structuredSubmissions"),
    ACKNOWLEDGEMENTS_AND_TRACES("acknowledgementsAndTraces"),
    SOURCE_DOCUMENT_REFERENCES("sourceDocumentReferences"),
}
