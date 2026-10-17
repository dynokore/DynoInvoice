package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/** The DSLContexts of the tests, built as the service-runtime wiring builds them (INV-Q-5275). */
object JooqKit {
    /** The module's DSLContext of [schema] over [runner] (which is also the current connection). */
    fun module(
        schema: String,
        tenants: TenantContext,
        runner: TransactionRunner,
    ): DSLContext = TenancyJooq.dslContext(OutboxSchema.jooqTables(schema), tenants, runner)

    /** The event-log DSLContext over [scope]'s own runner, with the topic tables of [catalogue]. */
    fun eventLog(
        scope: TenantScope,
        catalogue: EventCatalogue,
    ): DSLContext =
        TenancyJooq.dslContext(
            EventLogSchema.jooqTables(
                (
                    catalogue.entries().map { it.topic } + catalogue.replayTopics().map { it.topic } +
                        catalogue.requestTopics().map { it.topic }
                ).toSet(),
            ),
            scope.tenants,
            scope.transactions,
        )
}
