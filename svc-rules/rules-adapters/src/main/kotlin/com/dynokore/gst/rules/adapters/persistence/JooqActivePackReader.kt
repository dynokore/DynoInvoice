package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.application.ActivePack
import com.dynokore.gst.rules.application.ActivePackPosition
import com.dynokore.gst.rules.application.ActivePackReader
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * The active regulatory packs of the platform layer, read through `rules.list_active_regulatory_packs`: a SECURITY
 * DEFINER function owned by `rules_sys`, because the installer call carries no tenant and the tenant read policy of
 * `rules.pack_registry` is not widened. tenancy-jooq still wants a binding on the thread, so the platform's own is
 * bound, as the platform write path does. jOOQ only (INV-T-4201).
 */
class JooqActivePackReader(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
) : ActivePackReader {
    override fun page(
        after: ActivePackPosition?,
        limit: Int,
    ): List<ActivePack> {
        val document = platform.bindPlatform(ACTOR) { transactions.inTransaction { read(after, limit) } }
        return json.readTree(checkNotNull(document) { "the function returned no document" }.data()).toList().map {
            ActivePack(
                packType = it.get("packType").asString(),
                version = it.get("version").asString(),
                status = it.get("status").asString(),
                minEngineVersion = it.get("minEngineVersion").asString(),
                effectiveFrom = date(it.get("effectiveFrom")),
                effectiveTo = date(it.get("effectiveTo")),
            )
        }
    }

    private fun read(
        after: ActivePackPosition?,
        limit: Int,
    ): JSONB? =
        dsl
            .select(
                DSL.function(
                    DSL.name("rules", "list_active_regulatory_packs"),
                    SQLDataType.JSONB,
                    DSL.`val`(limit, SQLDataType.INTEGER),
                    DSL.`val`(after?.packType, SQLDataType.CLOB),
                    DSL.`val`(after?.version, SQLDataType.CLOB),
                ),
            ).fetchOne(0, JSONB::class.java)

    private fun date(node: JsonNode?): LocalDate? = node?.takeUnless { it.isNull }?.asString()?.let(LocalDate::parse)

    private val json = JsonMapper()

    private companion object {
        /** The workload principal the read runs as (ARC-21). */
        const val ACTOR = "svc:rules-svc/op:list-active-regulatory-packs"
    }
}
