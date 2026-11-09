package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.SCOPE_PACK_ACTIVATION
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.SCOPE_PACK_VERSION
import com.dynokore.gst.masterdata.adapters.rate.ApprovedSource
import com.dynokore.gst.masterdata.adapters.rate.ScopePackStore
import org.jooq.DSLContext
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * TPL-11. The scope pack replica of the bound tenant (row-level security on the signed binding). A row is inserted
 * once: a replayed `RulePackStaged` or `RulePackActivated` finds its primary key and changes nothing.
 */
class JooqScopePackStore(
    private val dsl: DSLContext,
    private val mapper: ObjectMapper,
) : ScopePackStore {
    override fun stage(
        packIdentifier: String,
        version: String,
        sources: List<ApprovedSource>,
        recordedAt: Instant,
    ): Boolean {
        val json =
            mapper.writeValueAsString(sources.map { mapOf("sourceId" to it.sourceId, "bases" to it.bases.sorted()) })
        return dsl
            .insertInto(SCOPE_PACK_VERSION)
            .set(SCOPE_PACK_VERSION.PACK_IDENTIFIER, packIdentifier)
            .set(SCOPE_PACK_VERSION.PACK_VERSION, version)
            .set(SCOPE_PACK_VERSION.EXCHANGE_RATE_SOURCES, json)
            .set(SCOPE_PACK_VERSION.RECORDED_AT, recordedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0
    }

    override fun activate(
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
        recordedAt: Instant,
    ): Boolean {
        val latest =
            dsl
                .select(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM)
                .from(SCOPE_PACK_ACTIVATION)
                .where(SCOPE_PACK_ACTIVATION.PACK_IDENTIFIER.eq(packIdentifier))
                .orderBy(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM.desc())
                .limit(1)
                .fetchOne()
                ?.value1()
        if (latest != null && !effectiveFrom.isAfter(latest)) return false
        return dsl
            .insertInto(SCOPE_PACK_ACTIVATION)
            .set(SCOPE_PACK_ACTIVATION.PACK_IDENTIFIER, packIdentifier)
            .set(SCOPE_PACK_ACTIVATION.PACK_VERSION, version)
            .set(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM, effectiveFrom)
            .set(SCOPE_PACK_ACTIVATION.RECORDED_AT, recordedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0
    }

    override fun approvedOn(on: LocalDate): List<ApprovedSource>? {
        val active =
            dsl
                .selectFrom(SCOPE_PACK_ACTIVATION)
                .where(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM.le(on))
                .orderBy(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM.desc())
                .fetch()
                .distinctBy { it.packIdentifier }
        val lists =
            active.mapNotNull { activation ->
                dsl
                    .select(SCOPE_PACK_VERSION.EXCHANGE_RATE_SOURCES)
                    .from(SCOPE_PACK_VERSION)
                    .where(SCOPE_PACK_VERSION.PACK_IDENTIFIER.eq(activation.packIdentifier))
                    .and(SCOPE_PACK_VERSION.PACK_VERSION.eq(activation.packVersion))
                    .fetchOne()
                    ?.value1()
            }
        if (lists.isEmpty()) return null
        return lists.flatMap { json ->
            mapper.readTree(json).values().map { node ->
                ApprovedSource(node["sourceId"].asString(), node["bases"].values().map { it.asString() }.toSet())
            }
        }
    }
}
