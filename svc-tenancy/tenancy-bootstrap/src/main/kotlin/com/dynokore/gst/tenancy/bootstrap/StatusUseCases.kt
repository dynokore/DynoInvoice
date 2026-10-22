package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.EntityProfileChangedOutbox
import com.dynokore.gst.tenancy.application.status.EntityLifecycleGuard
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusCommands
import com.dynokore.gst.tenancy.application.status.EntityStatusQueries
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/** TPL-18: the entity-status use cases (INV-T-4926), constructed here and nowhere else. */
@Configuration(proxyBeanMethods = false)
class StatusUseCases {
    @Bean
    fun entityProfileChangePublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
    ): EntityProfileChangePublisher = EntityProfileChangedOutbox(outbox, mapper)

    @Bean
    fun entityStatusCommands(
        tx: TransactionRunner,
        store: StructureStore,
        statuses: EntityStatusStore,
        publisher: EntityProfileChangePublisher,
    ): EntityStatusCommands = EntityStatusCommands(tx, store, statuses, publisher)

    @Bean
    fun entityStatusQueries(
        tx: TransactionRunner,
        store: StructureStore,
        statuses: EntityStatusStore,
    ): EntityStatusQueries = EntityStatusQueries(tx, store, statuses)

    @Bean
    fun entityLifecycleGuard(
        tx: TransactionRunner,
        store: StructureStore,
        statuses: EntityStatusStore,
    ): EntityLifecycleGuard = EntityLifecycleGuard(tx, store, statuses)
}
