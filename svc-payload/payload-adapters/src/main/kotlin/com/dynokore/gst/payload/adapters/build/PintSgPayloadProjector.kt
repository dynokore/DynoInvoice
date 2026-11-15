package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.projection.BuildResult
import com.dynokore.gst.payload.adapters.projection.BuilderKey
import com.dynokore.gst.payload.adapters.projection.BuilderLookup
import com.dynokore.gst.payload.adapters.projection.DocumentBuilderRegistry
import com.dynokore.gst.payload.adapters.projection.EndpointInputs
import com.dynokore.gst.payload.application.build.BuildInput
import com.dynokore.gst.payload.application.build.BuildNotReady
import com.dynokore.gst.payload.application.build.PayloadProjector
import com.dynokore.gst.payload.application.build.Projection
import com.dynokore.gst.payload.application.build.SpecificationFamilySource
import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.OffNetworkPrefixSource
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.header.DocumentHeaderInput
import com.dynokore.gst.payload.domain.party.IdentityTriple
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyInput
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Projects the stored canonical document of an ordinary document to PINT-SG XML through the builders of
 * [DocumentBuilderRegistry.standard]. A document of an aggregate (non-null `aggregateId`) is not built here: its MDE
 * placeholder literals are a following slice, so it throws [IllegalStateException] and nothing is published. A null
 * direction is blocked, never defaulted. [offNetworkPrefix] answers the off-network prefix source of the active MDE
 * pack, or null while none is active, which is [BuildNotReady].
 */
class PintSgPayloadProjector(
    private val families: SpecificationFamilySource,
    private val offNetworkPrefix: () -> OffNetworkPrefixSource?,
    private val registry: DocumentBuilderRegistry = DocumentBuilderRegistry.standard(),
    private val mapper: ObjectMapper = ObjectMapper(),
) : PayloadProjector {
    override fun project(
        input: BuildInput,
        submissionType: String,
    ): Projection {
        check(input.aggregateId == null) { "an aggregate document is built by a following slice" }
        val direction = input.direction ?: return blocked(DIRECTION_ABSENT)
        val document = mapper.readTree(input.canonicalDocument)
        val typeCode = text(document.path("documentTypeCode").path("value")) ?: return blocked(TYPE_CODE_ABSENT)
        val party = parties(document) ?: return blocked(PARTY_ABSENT)
        val family = SpecificationFamily.valueOf(families.familyOf(input.entityRef))
        val prefix = offNetworkPrefix() ?: throw BuildNotReady("no active MDE pack")
        val builder =
            when (val lookup = registry.find(BuilderKey(family, typeCode))) {
                is BuilderLookup.Found -> lookup.builder
                is BuilderLookup.NoBuilder -> return blocked("NO_BUILDER", "key" to "$family:$typeCode")
            }
        val result =
            builder.build(
                party,
                header(document),
                Direction.valueOf(direction),
                family,
                SubmissionType.valueOf(submissionType),
                EndpointInputs(input.counterpartyOnNetwork, prefix),
            )
        return when (result) {
            is BuildResult.Built -> {
                Projection.Built(result.xml.toByteArray(Charsets.UTF_8))
            }

            is BuildResult.Refused -> {
                blocked("REFUSED", "refusal" to result.reason.name)
            }

            is BuildResult.EndpointRefused -> {
                blocked("ENDPOINT_REFUSED", "result" to result.result.toString())
            }

            is BuildResult.CustomizationIdUnpublished -> {
                blocked(
                    "CUSTOMIZATION_ID_UNPUBLISHED",
                    "family" to result.family.name,
                    "type" to result.submissionType.name,
                )
            }
        }
    }

    private fun parties(document: JsonNode): PartyIdentifierInput? {
        val parties = document.path("parties").path("value")
        val buyer = party(parties.path("buyer")) ?: return null
        val supplier = party(parties.path("supplier")) ?: return null
        return PartyIdentifierInput(buyer, supplier, triple(document.path("identityTriple").path("value")))
    }

    private fun party(node: JsonNode): PartyInput? {
        val scheme = text(node.path("identifierScheme")) ?: return null
        val value = text(node.path("identifierValue")) ?: return null
        return PartyInput(scheme, value)
    }

    private fun triple(node: JsonNode): IdentityTriple? {
        if (!node.isObject) return null
        return IdentityTriple(
            text(node.path("senderId")) ?: return null,
            text(node.path("supplierId")) ?: return null,
            text(node.path("supplierGstn")) ?: return null,
        )
    }

    private fun header(document: JsonNode): DocumentHeaderInput =
        DocumentHeaderInput(
            documentNumber = text(document.path("documentNumber").path("value")),
            documentDate = date(text(document.path("documentDate").path("value"))),
            invoiceNote = text(document.path("invoiceNote").path("value")),
        )

    private fun date(text: String?): LocalDate? =
        try {
            text?.let(LocalDate::parse)
        } catch (e: DateTimeParseException) {
            throw IllegalStateException("documentDate is not an ISO date: $text", e)
        }

    private fun text(node: JsonNode): String? = if (node.isString) node.asString() else null

    private fun blocked(
        reason: String,
        vararg details: Pair<String, String>,
    ): Projection.Blocked {
        val trace = mapper.createObjectNode().put("reason", reason)
        details.forEach { (k, v) -> trace.put(k, v) }
        return Projection.Blocked(mapper.writeValueAsString(trace))
    }

    companion object {
        const val DIRECTION_ABSENT = "DIRECTION_ABSENT"
        const val TYPE_CODE_ABSENT = "DOCUMENT_TYPE_CODE_ABSENT"
        const val PARTY_ABSENT = "PARTY_IDENTIFIER_ABSENT"
    }
}
