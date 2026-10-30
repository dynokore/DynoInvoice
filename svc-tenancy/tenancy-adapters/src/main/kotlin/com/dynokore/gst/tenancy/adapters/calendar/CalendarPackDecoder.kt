package com.dynokore.gst.tenancy.adapters.calendar

import com.dynokore.platform.calendarandperiods.CalendarPack
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.format.DateTimeParseException

/** The outcome of decoding a calendar pack release: a [Decoded] pack, or a [Refused] naming the first failing item. */
sealed interface CalendarPackDecoding {
    data class Decoded(
        val pack: CalendarPack,
    ) : CalendarPackDecoding

    /** [item] is the first failing item (a JSON location or a rule); never accompanied by a partial pack. */
    data class Refused(
        val item: String,
        val reason: String,
    ) : CalendarPackDecoding
}

/**
 * Turns the `artefact.contents` of a RulePackStaged payload into the [CalendarPack] of calendar-and-periods
 * (INV-T-5055; INV-REQ-83, INV-REQ-87, INV-REQ-88, INV-REQ-1117, INV-REQ-1279). Fail closed: any input that is not a
 * complete, valid calendar pack gives a [CalendarPackDecoding.Refused] and never a partial or defaulted pack.
 *
 * The contents are first validated against `calendar-pack-contents.schema.json` of the rules contract; only the
 * properties that schema names are read, so an unknown property is ignored (API-41, FR-K.4). `entrySources` is checked
 * by the schema and mapped to nothing: [CalendarPack] has no member for it (INV-Q-6556).
 */
class CalendarPackDecoder(
    private val mapper: ObjectMapper,
    private val schema: Schema = contentsSchema(),
) {
    /** Decodes a whole RulePackStaged payload given as JSON text. */
    fun decode(payloadJson: String): CalendarPackDecoding =
        try {
            val (version, contents) = envelope(payloadJson)
            decodeContents(version, contents)
        } catch (e: PackRefusal) {
            CalendarPackDecoding.Refused(e.item, e.message)
        }

    /** The version and the `artefact.contents` of a payload of [PACK_TYPE]; a [PackRefusal] otherwise. */
    private fun envelope(payloadJson: String): Pair<String, JsonNode> {
        val payload = parse(payloadJson)
        val packType = payload.get("packType")
        refuseUnless(packType?.takeIf { it.isString }?.asString() == PACK_TYPE, "packType") {
            "only packType $PACK_TYPE is decoded, found ${packType ?: "none"}"
        }
        val version = payload.get("version")?.takeIf { it.isString }?.asString()
        refuseUnless(!version.isNullOrBlank(), "version") { "the payload has no version" }
        val contents = payload.get("artefact")?.get("contents")
        refuseUnless(contents != null, "artefact.contents") { "the payload has no artefact.contents" }
        return checkNotNull(version) to checkNotNull(contents)
    }

    private fun parse(payloadJson: String): JsonNode {
        val payload =
            try {
                mapper.readTree(payloadJson)
            } catch (e: tools.jackson.core.JacksonException) {
                throw PackRefusal("payload", "the payload is not JSON: ${e.originalMessage}", e)
            }
        refuseUnless(payload.isObject, "payload") { "the payload is not a JSON object" }
        return payload
    }

    private fun refuseUnless(
        ok: Boolean,
        item: String,
        reason: () -> String,
    ) {
        if (!ok) throw PackRefusal(item, reason())
    }

    private fun decodeContents(
        version: String,
        contents: JsonNode,
    ): CalendarPackDecoding {
        val error =
            schema
                .validate(mapper.writeValueAsString(contents), InputFormat.JSON)
                .sortedWith(compareBy({ it.instanceLocation.toString() }, { it.message }))
                .firstOrNull()
        if (error != null) return refused("contents${error.instanceLocation}", error.message)
        return try {
            CalendarPackDecoding.Decoded(CalendarContentsMapper.map(version, contents))
        } catch (e: PackRefusal) {
            CalendarPackDecoding.Refused(e.item, e.message)
        } catch (e: DateTimeParseException) {
            refused("contents", "not a date: ${e.parsedString}")
        } catch (e: IllegalArgumentException) {
            // The CalendarPack constructor refuses what no schema expresses; its message names the item.
            refused("CalendarPack", e.message ?: "refused by CalendarPack")
        }
    }

    private fun refused(
        item: String,
        reason: String,
    ) = CalendarPackDecoding.Refused(item, reason)

    companion object {
        /** The only pack type this decoder applies to, by equality. */
        const val PACK_TYPE = "calendar"
        private const val SCHEMA_RESOURCE = "gst/contracts/rules/schemas/calendar-pack-contents.schema.json"

        /** The schema of the pinned rules contract, staged on the classpath by the build. */
        fun contentsSchema(): Schema =
            SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(
                    checkNotNull(CalendarPackDecoder::class.java.classLoader.getResourceAsStream(SCHEMA_RESOURCE)) {
                        "$SCHEMA_RESOURCE is not on the classpath"
                    }.use { it.readBytes().toString(Charsets.UTF_8) },
                )
    }
}
