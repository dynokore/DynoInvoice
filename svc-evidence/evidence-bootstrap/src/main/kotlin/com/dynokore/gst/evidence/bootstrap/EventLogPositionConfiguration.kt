package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.outboxandqueue.EventLogPositions
import com.dynokore.platform.outboxandqueue.EventLogSchema
import com.dynokore.platform.outboxandqueue.JdbcEventLogPositions
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.InstallationBindingKey
import com.dynokore.platform.serviceruntime.composition.EventLogScopes
import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.dynokore.platform.serviceruntime.pool.EventLogGate
import com.dynokore.platform.tenancyjooq.TenancyJooq
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
        val eventLogScope =
            EventLogScopes.of(eventLog.getIfAvailable(), scope, gate.getIfAvailable(), bindingKey.getIfAvailable())
        val dsl =
            TenancyJooq.dslContext(
                EventLogSchema.jooqTables(emptyList()),
                eventLogScope.tenants,
                eventLogScope.transactions,
            )
        return JdbcEventLogPositions(eventLogScope, dsl)
    }
}
