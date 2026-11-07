package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.RefusalReason

/** The key of a builder: one per specification family and document type (INV-Q-7106 A item 3). */
data class BuilderKey(
    val specificationFamily: SpecificationFamily,
    val documentTypeCode: String,
)

/**
 * The outcome of a build. `senderId` is returned beside the document; its place in the output is the binding
 * table's (INV-Q-7142).
 */
sealed interface BuildResult {
    data class Built(
        val xml: String,
        val senderId: String,
    ) : BuildResult

    data class Refused(
        val reason: RefusalReason,
    ) : BuildResult
}

/** A hand-written builder that writes the generated JAXB objects of one specification family and document type. */
interface DocumentBuilder {
    val key: BuilderKey

    fun build(input: PartyIdentifierInput): BuildResult
}

/** The outcome of a registry lookup. */
sealed interface BuilderLookup {
    data class Found(
        val builder: DocumentBuilder,
    ) : BuilderLookup

    data class NoBuilder(
        val key: BuilderKey,
    ) : BuilderLookup
}

/** Holds the builders by key; a key without a builder is refused, there is no default builder. */
class DocumentBuilderRegistry(
    builders: List<DocumentBuilder>,
) {
    private val byKey: Map<BuilderKey, DocumentBuilder> =
        builders.associateBy { it.key }.also {
            require(it.size == builders.size) { "two builders for one key" }
        }

    fun find(key: BuilderKey): BuilderLookup =
        byKey[key]?.let { BuilderLookup.Found(it) } ?: BuilderLookup.NoBuilder(key)

    fun keys(): Set<BuilderKey> = byKey.keys

    companion object {
        /** The two builders of INV-T-7178: 380 (Invoice) and 381 (CreditNote) of PINT-SG-1.0. */
        fun standard(): DocumentBuilderRegistry =
            DocumentBuilderRegistry(listOf(PintSgInvoiceBuilder(), PintSgCreditNoteBuilder()))
    }
}
