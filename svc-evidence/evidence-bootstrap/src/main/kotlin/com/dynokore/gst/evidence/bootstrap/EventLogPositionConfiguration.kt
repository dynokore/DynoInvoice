package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.outboxandqueue.EventLogPositions
import com.dynokore.platform.outboxandqueue.EventLogSchema
import com.dynokore.platform.outboxandqueue.JdbcEventLogPositions
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.InstallationBindingKey
import com.dynokore.platform.serviceruntime.composition.OutboxAndQueueWiring
import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.dynokore.platform.serviceruntime.pool.EventLogGate
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * The record-position write of evidence-svc (INV-REQ-3650 b2; coordinator ruling INV-Q-4266): [JdbcEventLogPositions]
 * on the event-log scope, composed as service-runtime composes the scope of its event-log reader: its own runner on
 * the separate event-log pool behind that pool's gate when `gst.db.event-log-url` names one, else the application
 * scope. It is evidence-svc's only write on `eventlog` and goes through the sys-owned function alone.
 */
@Configuration(proxyBeanMethods = false)
class EventLogPositionConfiguration {
    @Bean
    fun eventLogPositions(
        @Qualifier(ServiceDataSources.EVENT_LOG) eventLog: ObjectProvider<DataSource>,
        scope: TenantScope,
        gate: ObjectProvider<EventLogGate>,
        bindingKey: ObjectProvider<InstallationBindingKey>,
    ): EventLogPositions {
        // copy of service-runtime's event-log scope; INV-T-12072 replaces it with INV-T-12071's EventLogScopes.of
        val eventLogScope =
            eventLog.getIfAvailable()?.let {
                val transactions =
                    TransactionRunnerImpl(
                        OutboxAndQueueWiring.handOff(it, gate.getIfAvailable()),
                        scope.tenants,
                        null,
                        bindingKey.getIfAvailable()?.source,
                    )
                TenantScope(scope.tenants, transactions, transactions, scope.workloadPrincipal, scope.platform)
            } ?: scope
        val dsl =
            TenancyJooq.dslContext(
                EventLogSchema.jooqTables(emptyList()),
                eventLogScope.tenants,
                eventLogScope.transactions,
            )
        return JdbcEventLogPositions(eventLogScope, dsl)
    }
}
