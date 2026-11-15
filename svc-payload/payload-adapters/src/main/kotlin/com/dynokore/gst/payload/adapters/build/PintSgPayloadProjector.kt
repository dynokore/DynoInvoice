package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.projection.BuildResult
import com.dynokore.gst.payload.adapters.projection.BuilderKey
import com.dynokore.gst.payload.adapters.projection.BuilderLookup
import com.dynokore.gst.payload.adapters.projection.DocumentBuilder
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
        return try {
            build(input, submissionType)
        } catch (e: Blocking) {
            Projection.Blocked(e.trace)
        }
    }

    private fun build(
        input: BuildInput,
        submissionType: String,
    ): Projection {
        val direction = input.direction ?: throw blocking(DIRECTION_ABSENT)
        val document = mapper.readTree(input.canonicalDocument)
        val typeCode = typeCodeOf(document)
        val family = SpecificationFamily.valueOf(families.familyOf(input.entityRef))
        val prefix = offNetworkPrefix() ?: throw BuildNotReady("no active MDE pack")
        val result =
            builderOf(BuilderKey(family, typeCode)).build(
                parties(document),
                DocumentFields.header(document),
                Direction.valueOf(direction),
                family,
                SubmissionType.valueOf(submissionType),
                EndpointInputs(input.counterpartyOnNetwork, prefix),
            )
        return projection(result)
    }

    private fun typeCodeOf(document: JsonNode): String =
        DocumentFields.text(document.path("documentTypeCode").path("value")) ?: throw blocking(TYPE_CODE_ABSENT)

    private fun builderOf(key: BuilderKey): DocumentBuilder =
        when (val lookup = registry.find(key)) {
            is BuilderLookup.Found -> lookup.builder

            is BuilderLookup.NoBuilder -> throw blocking(
                "NO_BUILDER",
                "key" to "${key.specificationFamily}:${key.documentTypeCode}",
            )
        }

    private fun projection(result: BuildResult): Projection =
        when (result) {
            is BuildResult.Built -> {
                Projection.Built(result.xml.toByteArray(Charsets.UTF_8))
            }

            is BuildResult.Refused -> {
                throw blocking("REFUSED", "refusal" to result.reason.name)
            }

            is BuildResult.EndpointRefused -> {
                throw blocking("ENDPOINT_REFUSED", "result" to result.result.toString())
            }

            is BuildResult.CustomizationIdUnpublished -> {
                throw blocking(
                    "CUSTOMIZATION_ID_UNPUBLISHED",
                    "family" to result.family.name,
                    "type" to result.submissionType.name,
                )
            }
        }

    private fun parties(document: JsonNode): PartyIdentifierInput {
        val parties = document.path("parties").path("value")
        return PartyIdentifierInput(
            party(parties.path("buyer")),
            party(parties.path("supplier")),
            DocumentFields.triple(document.path("identityTriple").path("value")),
        )
    }

    private fun party(node: JsonNode): PartyInput =
        PartyInput(
            DocumentFields.text(node.path("identifierScheme")) ?: throw blocking(PARTY_ABSENT),
            DocumentFields.text(node.path("identifierValue")) ?: throw blocking(PARTY_ABSENT),
        )

    private fun blocking(
        reason: String,
        vararg details: Pair<String, String>,
    ): Blocking {
        val trace = mapper.createObjectNode().put("reason", reason)
        details.forEach { (k, v) -> trace.put(k, v) }
        return Blocking(mapper.writeValueAsString(trace))
    }

    /** The projection cannot be built; carries the validation trace. Stackless: it is control flow. */
    private class Blocking(
        val trace: String,
    ) : RuntimeException(null, null, false, false)

    companion object {
        const val DIRECTION_ABSENT = "DIRECTION_ABSENT"
        const val TYPE_CODE_ABSENT = "DOCUMENT_TYPE_CODE_ABSENT"
        const val PARTY_ABSENT = "PARTY_IDENTIFIER_ABSENT"
    }
}

/** Reads the plain members of a stored canonical document; an absent or non-text member is null. */
private object DocumentFields {
    private const val TRIPLE_SIZE = 3

    fun triple(node: JsonNode): IdentityTriple? {
        val ids =
            listOfNotNull(text(node.path("senderId")), text(node.path("supplierId")), text(node.path("supplierGstn")))
        return if (ids.size == TRIPLE_SIZE) IdentityTriple(ids[0], ids[1], ids[2]) else null
    }

    fun header(document: JsonNode): DocumentHeaderInput =
        DocumentHeaderInput(
            documentNumber = text(document.path("documentNumber").path("value")),
            documentDate = date(text(document.path("documentDate").path("value"))),
            invoiceNote = text(document.path("invoiceNote").path("value")),
        )

    fun date(text: String?): LocalDate? =
        try {
            text?.let(LocalDate::parse)
        } catch (e: DateTimeParseException) {
            throw IllegalStateException("documentDate is not an ISO date: $text", e)
        }

    fun text(node: JsonNode): String? = if (node.isString) node.asString() else null
}
