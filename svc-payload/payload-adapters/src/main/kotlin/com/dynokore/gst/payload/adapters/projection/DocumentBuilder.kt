package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.EndpointIdResult
import com.dynokore.gst.payload.domain.OffNetworkPrefixSource
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.header.DocumentHeaderInput
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

    /** The endpoint ID (IBT-049) is not built (INV-Q-7189 A): no XML; nothing is held and no PAY code is raised. */
    data class EndpointRefused(
        val result: EndpointIdResult,
    ) : BuildResult

    /** The pair has no published Customization ID (INV-Q-7180 A): no XML, no IBT-024 and no default. */
    data class CustomizationIdUnpublished(
        val family: SpecificationFamily,
        val submissionType: SubmissionType,
    ) : BuildResult
}

/**
 * What the endpoint ID (IBT-049) needs besides the buyer and the submission type: `counterpartyOnNetwork` of the
 * canonical version and the off-network prefix source of the active pack version (INV-Q-7189 A).
 */
data class EndpointInputs(
    val onNetwork: Boolean?,
    val offNetworkPrefix: OffNetworkPrefixSource,
)

/** A hand-written builder that writes the generated JAXB objects of one specification family and document type. */
interface DocumentBuilder {
    val key: BuilderKey

    @Suppress("LongParameterList") // the five inputs of INV-T-7178 plus the endpoint inputs of INV-T-7193
    fun build(
        input: PartyIdentifierInput,
        header: DocumentHeaderInput,
        direction: Direction,
        family: SpecificationFamily,
        submissionType: SubmissionType,
        endpoint: EndpointInputs,
    ): BuildResult
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
