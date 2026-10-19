package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.INSTALLATION_FACT
import com.dynokore.gst.tenancy.application.installation.InstallationFact
import com.dynokore.gst.tenancy.application.installation.InstallationFactRepository
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.ZoneOffset

/**
 * Runs [block] in one transaction bound to the reserved platform tenant: installation facts are installation-scoped,
 * not tenant data, so no tenant owns them (the table carries no tenant column and no row-level-security policy).
 */
class PlatformScope(
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
    private val actor: String,
) {
    fun <T> run(block: () -> T): T = tenants.bind(TenantId.PLATFORM, actor) { tx.inTransaction(block) }
}

/** INV-Q-4898: the one installation-fact table, written by INV-T-3290 and the entitlement task through this class. */
class JooqInstallationFactRepository(
    private val dsl: DSLContext,
    private val scope: PlatformScope,
) : InstallationFactRepository {
    override fun put(fact: InstallationFact) {
        scope.run {
            dsl
                .insertInto(INSTALLATION_FACT)
                .set(INSTALLATION_FACT.FACT_KEY, fact.key)
                .set(INSTALLATION_FACT.FACT_VALUE, fact.value)
                .set(INSTALLATION_FACT.RECORDED_AT, fact.recordedAt.atOffset(ZoneOffset.UTC))
                .onConflict(INSTALLATION_FACT.FACT_KEY)
                .doUpdate()
                .set(INSTALLATION_FACT.FACT_VALUE, fact.value)
                .set(INSTALLATION_FACT.RECORDED_AT, fact.recordedAt.atOffset(ZoneOffset.UTC))
                .set(INSTALLATION_FACT.VERSION, INSTALLATION_FACT.VERSION.plus(1L))
                .execute()
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

    private fun fact(row: com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.records.InstallationFactRecord) =
        InstallationFact(
            row.factKey.column("fact_key"),
            row.factValue.column("fact_value"),
            row.recordedAt.column("recorded_at").toInstant(),
        )
}
