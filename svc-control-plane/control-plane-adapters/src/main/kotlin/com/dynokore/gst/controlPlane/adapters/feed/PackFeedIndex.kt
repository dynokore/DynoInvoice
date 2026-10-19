package com.dynokore.gst.controlPlane.adapters.feed

import com.dynokore.gst.controlPlane.adapters.bundle.CanonicalJson
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_FEED_INDEX
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/** One entry of the pack feed index; plain strings, so a test can write a value the schema refuses. */
data class FeedEntry(
    val packRef: String,
    val packType: String,
    val version: String,
    val effectiveFrom: String,
    val minEngineVersion: String,
    val packContentHash: String,
    val supersedes: String?,
)

/** The document failed `pack-feed-index.schema.json`; nothing was written and the previous index is still served. */
class FeedIndexInvalid(
    val errors: List<String>,
) : RuntimeException("the pack feed index does not validate against its schema: ${errors.joinToString("; ")}", null, false, false)

/**
 * Writes the pack feed index (INV-REQ-1079, INV-AC-2074, INV-AC-2594): one entry per published pack version, a
 * superseded one included. The document is validated against `control-plane/schemas/pack-feed-index.schema.json` of the
 * pinned platform-contracts release (the build stages it as a resource, never a copy in the repository) before it is
 * stored; an invalid document is refused and the previous index stays served. A row is a generation; the highest is
 * served, and two writers racing for one generation make the second fail (fail closed).
 */
class PackFeedIndex(
    private val dsl: DSLContext,
    private val clock: Clock,
    schemaText: String = schemaFromClasspath(),
) {
    private val schema: Schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaText)

    /** Validates and stores the index of [entries]; returns the stored document. */
    fun write(entries: List<FeedEntry>): String {
        val document = CanonicalJson.text(mapOf("entries" to entries.map(::asMap)))
        val errors =
            schema.validate(document, InputFormat.JSON) { context ->
                context.executionConfig { config -> config.formatAssertionsEnabled(true) }
            }
        if (errors.isNotEmpty()) throw FeedIndexInvalid(errors.map { it.message })
        val next = (dsl.select(org.jooq.impl.DSL.max(PACK_FEED_INDEX.GENERATION)).from(PACK_FEED_INDEX).fetchSingle().value1() ?: 0) + 1
        dsl
            .insertInto(PACK_FEED_INDEX)
            .set(PACK_FEED_INDEX.GENERATION, next)
            .set(PACK_FEED_INDEX.DOCUMENT, document)
            .set(PACK_FEED_INDEX.RECORDED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .execute()
        return document
    }

    /** The index document the feed serves now, or null before the first publication. */
    fun current(): String? =
        dsl
            .select(PACK_FEED_INDEX.DOCUMENT)
            .from(PACK_FEED_INDEX)
            .orderBy(PACK_FEED_INDEX.GENERATION.desc())
            .limit(1)
            .fetchOne(PACK_FEED_INDEX.DOCUMENT)

    private fun asMap(entry: FeedEntry): Map<String, Any> =
        buildMap {
            put("packRef", entry.packRef)
            put("packType", entry.packType)
            put("version", entry.version)
            put("effectiveFrom", entry.effectiveFrom)
            put("minEngineVersion", entry.minEngineVersion)
            put("packContentHash", entry.packContentHash)
            entry.supersedes?.let { put("supersedes", it) }
        }

    companion object {
        const val SCHEMA_RESOURCE = "/contract-schemas/pack-feed-index.schema.json"

        fun schemaFromClasspath(): String =
            checkNotNull(PackFeedIndex::class.java.getResourceAsStream(SCHEMA_RESOURCE)) {
                "$SCHEMA_RESOURCE is not on the classpath: the build stages it from the pinned platform-contracts release"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
