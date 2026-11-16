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
 * [DocumentBuilderRegistry.standard]. A document of an aggregate (non-null `aggregateId`) gets the placeholder literal
 * of its aggregate type from the active MDE pack as its invoice note (IBT-022), replacing any note; a pack that gives
 * none, a term other than IBT-022 or an invalid entry blocks the build. A null
 * direction is blocked, never defaulted. [activePack] answers the one read of the active MDE pack of a build (its
 * version, off-network prefix source and contents), or null while none is active, which is [BuildNotReady].
 */
class PintSgPayloadProjector(
    private val families: SpecificationFamilySource,
    private val activePack: () -> ActivePackRead?,
    private val registry: DocumentBuilderRegistry = DocumentBuilderRegistry.standard(),
    private val mapper: ObjectMapper = ObjectMapper(),
) : PayloadProjector {
    override fun project(
        input: BuildInput,
        submissionType: String,
    ): Projection =
        try {
            build(input, submissionType)
        } catch (e: Blocking) {
            Projection.Blocked(e.trace, e.packVersion)
        }

    private fun build(
        input: BuildInput,
        submissionType: String,
    ): Projection {
        val direction = input.direction ?: throw blocking(DIRECTION_ABSENT)
        val document = mapper.readTree(input.canonicalDocument)
        val typeCode = typeCodeOf(document)
        val family = SpecificationFamily.valueOf(families.familyOf(input.familyRequest()))
        val pack = activePack() ?: throw BuildNotReady("no active MDE pack")
        return namingPack(pack.version) {
            val header =
                if (input.aggregateId == null) {
                    DocumentFields.header(document)
                } else {
                    DocumentFields.header(document, aggregateNote(document, pack.contents))
                }
            val result =
                builderOf(BuilderKey(family, typeCode)).build(
                    parties(document),
                    header,
                    Direction.valueOf(direction),
                    family,
                    SubmissionType.valueOf(submissionType),
                    EndpointInputs(input.counterpartyOnNetwork, pack.prefix),
                )
            projection(result)
        }
    }

    /** A block after the one read of the pack names the version that read answered. */
    private fun namingPack(
        version: String,
        block: () -> Projection,
    ): Projection =
        try {
            block()
        } catch (e: Blocking) {
            e.packVersion = version
            throw e
        }

    private fun aggregateNote(
        document: JsonNode,
        contents: String,
    ): String {
        val type =
            DocumentFields.text(document.path("aggregateType").path("value"))
                ?: throw blocking(AGGREGATE_TYPE_ABSENT)
        return AggregateNoteRule(contents) { reason, detail -> blocking(reason, *detail) }.noteOf(type)
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
        var packVersion: String? = null,
    ) : RuntimeException(null, null, false, false)

    companion object {
        const val DIRECTION_ABSENT = "DIRECTION_ABSENT"
        const val TYPE_CODE_ABSENT = "DOCUMENT_TYPE_CODE_ABSENT"
        const val AGGREGATE_TYPE_ABSENT = "AGGREGATE_TYPE_ABSENT"
        const val AGGREGATE_LITERAL_ABSENT = "AGGREGATE_LITERAL_ABSENT"
        const val AGGREGATE_LITERAL_INVALID = "AGGREGATE_LITERAL_INVALID"
        const val AGGREGATE_LITERAL_TERM_UNSUPPORTED = "AGGREGATE_LITERAL_TERM_UNSUPPORTED"
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

    fun header(
        document: JsonNode,
        note: String? = text(document.path("invoiceNote").path("value")),
    ): DocumentHeaderInput =
        DocumentHeaderInput(
            documentNumber = text(document.path("documentNumber").path("value")),
            documentDate = date(text(document.path("documentDate").path("value"))),
            invoiceNote = note,
        )

    fun date(text: String?): LocalDate? =
        try {
            text?.let(LocalDate::parse)
        } catch (e: DateTimeParseException) {
            throw IllegalStateException("documentDate is not an ISO date: $text", e)
        }

    fun text(node: JsonNode): String? = if (node.isString) node.asString() else null
}

/** The note an aggregate of [type] gets from the pack [contents]; [fail] makes the blocking exception of a reason. */
private class AggregateNoteRule(
    private val contents: String,
    private val fail: (String, Array<Pair<String, String>>) -> Throwable,
) {
    fun noteOf(type: String): String {
        val entry = literalOf(type)
        if (entry.businessTermId != NOTE_TERM) {
            throw fail(
                PintSgPayloadProjector.AGGREGATE_LITERAL_TERM_UNSUPPORTED,
                arrayOf("businessTermId" to entry.businessTermId),
            )
        }
        if (entry.literal != type) {
            throw fail(PintSgPayloadProjector.AGGREGATE_LITERAL_INVALID, arrayOf("aggregateType" to type))
        }
        return entry.literal
    }

    private fun literalOf(type: String): AggregateLiteral =
        try {
            MdePackAggregateLiterals(contents).literalFor(type)
        } catch (e: AggregateLiteralParseException) {
            throw fail(PintSgPayloadProjector.AGGREGATE_LITERAL_INVALID, arrayOf("detail" to (e.message ?: "")))
        } ?: throw fail(PintSgPayloadProjector.AGGREGATE_LITERAL_ABSENT, arrayOf("aggregateType" to type))

    private companion object {
        const val NOTE_TERM = "IBT-022"
    }
}
