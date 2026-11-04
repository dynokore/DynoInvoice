package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackActivatedHandler
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackDecoder
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackStagedHandler
import com.dynokore.gst.tenancy.adapters.calendar.ConsumedCalendarEvents
import com.dynokore.gst.tenancy.adapters.election.ConsumedElectionEvents
import com.dynokore.gst.tenancy.adapters.persistence.JooqCalendarPackReplica
import com.dynokore.gst.tenancy.adapters.persistence.JooqCalendarPackSource
import com.dynokore.gst.tenancy.application.period.CalendarPackReplica
import com.dynokore.gst.tenancy.application.period.PackActivationRecompute
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * TPL-18: the binding of the port `CalendarPackSource` (INV-T-4930, INV-Q-9725 A) to the replica of calendar packs fed
 * by RulePackStaged and RulePackActivated. The consumers have their own names (own cursors) on the topics the scope
 * pack consumers of ElectionUseCases read.
 */
@Configuration(proxyBeanMethods = false)
class CalendarPackUseCases {
    @Bean
    fun calendarPackDecoder(mapper: ObjectMapper): CalendarPackDecoder = CalendarPackDecoder(mapper)

    @Bean
    fun calendarPackReplica(
        dsl: DSLContext,
        tenants: TenantContext,
    ): CalendarPackReplica = JooqCalendarPackReplica(dsl, tenants)

    // the fixture adapter of the port, where a test profile has one, takes precedence in
    // PeriodUseCases.periodPlanner (INV-Q-4900 A); the production profile has only this one.
    @Bean
    fun calendarPackSource(
        dsl: DSLContext,
        tenants: TenantContext,
        decoder: CalendarPackDecoder,
    ): JooqCalendarPackSource = JooqCalendarPackSource(dsl, tenants, decoder)

    /** The calendar pack consumers: their own names on the topics the scope pack consumers read (own cursors). */
    @Bean
    fun calendarPackStagedConsumer(
        replica: CalendarPackReplica,
        mapper: ObjectMapper,
        decoder: CalendarPackDecoder,
        deps: ConsumerDependencies,
        effects: PackActivationRecompute,
    ): InboxConsumer =
        deps.consumer(
            ConsumedCalendarEvents.STAGED_CONSUMER,
            ConsumedElectionEvents.RULE_PACK_STAGED_TOPIC,
            CalendarPackStagedHandler(replica, mapper, decoder, effects = effects),
        )

    @Bean
    fun calendarPackActivatedConsumer(
        replica: CalendarPackReplica,
        mapper: ObjectMapper,
        deps: ConsumerDependencies,
        effects: PackActivationRecompute,
    ): InboxConsumer =
        deps.consumer(
            ConsumedCalendarEvents.ACTIVATED_CONSUMER,
            ConsumedElectionEvents.RULE_PACK_ACTIVATED_TOPIC,
            CalendarPackActivatedHandler(replica, mapper, effects = effects),
        )
}
