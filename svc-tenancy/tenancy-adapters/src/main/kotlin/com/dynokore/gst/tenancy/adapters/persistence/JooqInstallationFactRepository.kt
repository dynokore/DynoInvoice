package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.INSTALLATION_FACT
import com.dynokore.gst.tenancy.application.installation.InstallationFact
import com.dynokore.gst.tenancy.application.installation.InstallationFactRepository
import com.dynokore.platform.serviceruntime.persistence.AggregateStore
import com.dynokore.platform.serviceruntime.persistence.StaleAggregate
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.ZoneOffset

/** The generated POJO of `tenancy.installation_fact`, named apart from the application's fact. */
typealias InstallationFactRow = com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.pojos.InstallationFact

/**
 * Runs [block] in one transaction bound to the reserved platform tenant: installation facts are installation-scoped,
 * not tenant data, so no tenant owns them (the table carries no tenant column and no row-level-security policy).
 */
class PlatformScope(
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val actor: String,
) {
    fun <T> run(block: () -> T): T = platform.bindPlatform(actor) { tx.inTransaction(block) }
}

/**
 * INV-Q-4898: the one installation-fact table, written by INV-T-3290 and the entitlement task through this class.
 * Simple aggregate CRUD through service-runtime's `AggregateStore` (TPL-11); a stale version is a [StaleAggregate].
 */
class JooqInstallationFactRepository(
    private val dsl: DSLContext,
    private val scope: PlatformScope,
    private val store: AggregateStore<InstallationFactRow, String>,
) : InstallationFactRepository {
    override fun put(fact: InstallationFact) {
        scope.run { write(fact) { true } }
    }

    override fun putIfNotOlder(fact: InstallationFact) {
        scope.run {
            val at = fact.recordedAt.atOffset(ZoneOffset.UTC)
            write(fact) { stored -> !stored.recordedAt.column("recorded_at").isAfter(at) }
        }
    }

    private fun write(
        fact: InstallationFact,
        applies: (InstallationFactRow) -> Boolean,
    ) {
        val before = store.load(fact.key)
        val after = row(fact, before?.version ?: 0L)
        if (before == null) {
            store.insert(after)
        } else if (applies(before) && store.update(before, after) == UpdateOutcome.Conflict) {
            throw StaleAggregate("InstallationFact", before.version.column("version"))
        }
    }

    override fun find(key: String): InstallationFact? =
        scope.run {
            dsl
                .selectFrom(
                    INSTALLATION_FACT,
                ).where(INSTALLATION_FACT.FACT_KEY.eq(key))
                .fetchOne()
                ?.let(::fact)
        }

    override fun all(): List<InstallationFact> =
        scope.run {
            dsl
                .selectFrom(INSTALLATION_FACT)
                .orderBy(INSTALLATION_FACT.FACT_KEY)
                .fetch()
                .map(::fact)
        }

    private fun row(
        fact: InstallationFact,
        version: Long,
    ) = InstallationFactRow(fact.key, fact.value, fact.recordedAt.atOffset(ZoneOffset.UTC), version)

    private fun fact(row: com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.records.InstallationFactRecord) =
        InstallationFact(
            row.factKey.column("fact_key"),
            row.factValue.column("fact_value"),
            row.recordedAt.column("recorded_at").toInstant(),
        )
}
