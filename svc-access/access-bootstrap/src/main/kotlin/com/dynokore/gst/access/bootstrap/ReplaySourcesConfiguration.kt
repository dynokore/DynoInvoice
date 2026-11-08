package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AuthorityChangedReplaySource
import com.dynokore.gst.access.adapters.persistence.JooqReplayTenants
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * The producer-side replay source of access-svc (INV-T-9061, INV-Q-9047 A): one [ReplayStateSource] for
 * `PlatformEventCatalogue.AUTHORITY_CHANGED`. The responder, the replay and request topics and the driver that consume
 * it are a separate follow-up task and are not built here.
 */
@Configuration(proxyBeanMethods = false)
class ReplaySourcesConfiguration {
    @Bean
    fun authorityChangedReplaySource(
        dsl: DSLContext,
        tx: TransactionRunner,
        mapper: ObjectMapper,
    ): ReplayStateSource = AuthorityChangedReplaySource(dsl, mapper, JooqReplayTenants(dsl, tx))
}
