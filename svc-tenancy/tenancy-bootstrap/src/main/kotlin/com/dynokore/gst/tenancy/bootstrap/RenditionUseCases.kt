package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.JooqRenditionStore
import com.dynokore.gst.tenancy.adapters.rendition.ConsumedRenditionEvents
import com.dynokore.gst.tenancy.adapters.rendition.RenditionFindingChangedHandler
import com.dynokore.gst.tenancy.application.rendition.ApplyRenditionFinding
import com.dynokore.gst.tenancy.application.rendition.RecordRenditionResponsibility
import com.dynokore.gst.tenancy.application.rendition.RenditionAttestationStore
import com.dynokore.gst.tenancy.application.rendition.RenditionEntities
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * TPL-18: the rendition attestation use cases of INV-T-16410 constructed over the boundary adapters (INV-T-16411): the
 * store and the subscription to `RenditionFindingChanged` (INV-Q-16391).
 */
@Configuration(proxyBeanMethods = false)
class RenditionUseCases {
    /** One bean for both ports (the store and the entity check), so each port type resolves to one candidate. */
    @Bean
    fun jooqRenditionStore(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: ObjectProvider<Clock>,
    ): JooqRenditionStore =
        JooqRenditionStore(dsl, tenants) {
            // the store keeps microseconds (timestamptz)
            clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS)
        }

    @Bean
    fun recordRenditionResponsibility(
        tx: TransactionRunner,
        entities: RenditionEntities,
        store: RenditionAttestationStore,
    ): RecordRenditionResponsibility = RecordRenditionResponsibility(tx, entities, store)

    @Bean
    fun applyRenditionFinding(
        tx: TransactionRunner,
        store: RenditionAttestationStore,
    ): ApplyRenditionFinding = ApplyRenditionFinding(tx, store)

    @Bean
    fun renditionFindingChangedConsumer(
        apply: ApplyRenditionFinding,
        mapper: ObjectMapper,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            ConsumedRenditionEvents.CONSUMER,
            ConsumedRenditionEvents.TOPIC,
            RenditionFindingChangedHandler(apply, mapper),
        )
}
