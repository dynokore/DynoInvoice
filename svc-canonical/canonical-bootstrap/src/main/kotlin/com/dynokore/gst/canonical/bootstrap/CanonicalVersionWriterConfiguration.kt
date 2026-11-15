package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.OutboxCanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentAggregateRefs
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentStore
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.DocumentStore
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The single writer of document versions with its transactional-outbox publisher and the aggregate reference
 * store (INV-REQ-439). Correlation and causation come from the [InboundEventTrace] of the inbox consumer, as for
 * DocumentReleased.
 */
@Configuration(proxyBeanMethods = false)
class CanonicalVersionWriterConfiguration {
    /** No DocumentStore bean exists on main (PersistenceConfiguration supplies the schema only): supplied here. */
    @Bean
    fun documentStore(dsl: DSLContext): DocumentStore = JooqDocumentStore(dsl)

    @Bean
    fun documentAggregateRefs(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqDocumentAggregateRefs = JooqDocumentAggregateRefs(dsl, tenants)

    @Bean
    fun outboxCanonicalVersionCreatedPublisher(
        outbox: OutboxWriter,
        tenants: TenantContext,
        trace: InboundEventTrace,
    ): OutboxCanonicalVersionCreatedPublisher =
        OutboxCanonicalVersionCreatedPublisher(outbox, tenants, { trace.correlation() }, { trace.causation() })

    @Bean
    fun canonicalVersionWriter(
        store: DocumentStore,
        publisher: OutboxCanonicalVersionCreatedPublisher,
        aggregateRefs: JooqDocumentAggregateRefs,
    ): CanonicalVersionWriter = CanonicalVersionWriter(store, publisher, aggregateRefs)
}
