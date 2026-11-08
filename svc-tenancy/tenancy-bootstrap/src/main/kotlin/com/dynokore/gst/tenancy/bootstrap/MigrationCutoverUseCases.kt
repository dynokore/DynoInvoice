package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.JooqMigrationCutoverStore
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.migration.GetMigrationCutover
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverProfileExtras
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverStore
import com.dynokore.gst.tenancy.application.migration.RecordMigrationCutover
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** TPL-18: the migration cutover use case (INV-T-7382, INV-REQ-1109), constructed here and nowhere else. */
@Configuration(proxyBeanMethods = false)
class MigrationCutoverUseCases {
    @Bean
    fun migrationCutoverStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): MigrationCutoverStore = JooqMigrationCutoverStore(dsl, tenants)

    /**
     * The cutover entry joins the profile of this publish by attribute name, so the node carries it whether or not the
     * shared profile extras already do (the profile carry of INV-T-18861 adds it there).
     */
    @Bean
    @Suppress("LongParameterList") // the ports of the one use case
    fun recordMigrationCutover(
        tx: TransactionRunner,
        structure: StructureStore,
        store: MigrationCutoverStore,
        statuses: EntityStatusStore,
        publisher: EntityProfileChangePublisher,
        extras: ProfileExtras,
        authority: ActionAuthority,
        tenants: TenantContext,
        clock: ObjectProvider<Clock>,
    ): RecordMigrationCutover {
        val cutoverExtras = MigrationCutoverProfileExtras(store)
        val withCutover =
            ProfileExtras { entityRef ->
                val shared = extras.entries(entityRef)
                shared +
                    cutoverExtras.entries(entityRef).filter { entry -> shared.none { it.attribute == entry.attribute } }
            }
        return RecordMigrationCutover(
            tx,
            structure,
            store,
            statuses,
            publisher,
            withCutover,
            authority,
            tenants,
            clock.getIfAvailable(Clock::systemUTC),
        )
    }

    @Bean
    fun getMigrationCutover(
        tx: TransactionRunner,
        structure: StructureStore,
        store: MigrationCutoverStore,
        authority: ActionAuthority,
    ): GetMigrationCutover = GetMigrationCutover(tx, structure, store, authority)
}
