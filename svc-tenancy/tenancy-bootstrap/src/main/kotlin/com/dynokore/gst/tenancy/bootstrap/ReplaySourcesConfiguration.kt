package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.EntityProfileReplaySource
import com.dynokore.gst.tenancy.adapters.events.OrganisationStructureReplaySource
import com.dynokore.gst.tenancy.adapters.persistence.JooqReplayReader
import com.dynokore.gst.tenancy.adapters.persistence.ReplayReader
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * The producer-side replay sources of tenancy-svc (INV-T-9096, INV-Q-9081 A): one [ReplayStateSource] per event type
 * it emits as current state. The responder, the replay and request topics and the driver that consume them are not
 * built here (INV-Q-11721).
 */
@Configuration(proxyBeanMethods = false)
class ReplaySourcesConfiguration {
    @Bean
    fun replayReader(
        dsl: DSLContext,
        tenants: TenantContext,
    ): ReplayReader = JooqReplayReader(dsl, tenants)

    @Bean
    fun organisationStructureReplaySource(
        reader: ReplayReader,
        mapper: ObjectMapper,
    ): ReplayStateSource = OrganisationStructureReplaySource(reader, mapper)

    @Bean
    fun entityProfileReplaySource(
        reader: ReplayReader,
        mapper: ObjectMapper,
    ): ReplayStateSource = EntityProfileReplaySource(reader, mapper)
}
