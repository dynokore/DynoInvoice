package com.dynokore.gst.ingestion.bootstrap

import com.dynokore.gst.ingestion.adapters.events.TriggerCoverageChangedOutbox
import com.dynokore.gst.ingestion.adapters.permission.CoverageWritePermissionAdapter
import com.dynokore.gst.ingestion.adapters.persistence.TriggerCoverageJooqStore
import com.dynokore.gst.ingestion.application.permission.EntityPermission
import com.dynokore.gst.ingestion.application.triggers.CoverageWritePermission
import com.dynokore.gst.ingestion.application.triggers.MapTriggerClass
import com.dynokore.gst.ingestion.application.triggers.TriggerCoverageEvents
import com.dynokore.gst.ingestion.application.triggers.TriggerCoverageStore
import com.dynokore.gst.ingestion.application.triggers.WaiveTriggerClass
import com.dynokore.platform.outboxandqueue.OutboxWriter
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The one place that binds the trigger-coverage store, the event port, the permission port and the two use cases of
 * `mapTriggerClass` and `waiveTriggerClass` (composition root, ARC-03). The permission is decided by the scoped
 * [EntityPermission] of [PermissionConfiguration], never by a role name (INV-Q-10027).
 */
@Configuration(proxyBeanMethods = false)
class TriggerCoverageConfiguration {
    @Bean
    fun triggerCoverageStore(
        dsl: DSLContext,
        clock: ObjectProvider<Clock>,
    ): TriggerCoverageStore = TriggerCoverageJooqStore(dsl, clock.getIfAvailable { Clock.systemUTC() })

    @Bean
    fun triggerCoverageEvents(
        outbox: OutboxWriter,
        clock: ObjectProvider<Clock>,
    ): TriggerCoverageEvents = TriggerCoverageChangedOutbox(outbox, clock.getIfAvailable { Clock.systemUTC() })

    @Bean
    fun coverageWritePermission(permission: EntityPermission): CoverageWritePermission =
        CoverageWritePermissionAdapter(permission)

    @Bean
    fun mapTriggerClass(
        store: TriggerCoverageStore,
        events: TriggerCoverageEvents,
        permission: CoverageWritePermission,
    ): MapTriggerClass = MapTriggerClass(store, events, permission)

    @Bean
    fun waiveTriggerClass(
        store: TriggerCoverageStore,
        events: TriggerCoverageEvents,
        permission: CoverageWritePermission,
    ): WaiveTriggerClass = WaiveTriggerClass(store, events, permission)
}
