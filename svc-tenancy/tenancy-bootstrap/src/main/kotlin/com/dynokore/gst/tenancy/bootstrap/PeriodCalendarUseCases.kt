package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackDecoder
import com.dynokore.gst.tenancy.adapters.calendar.ConsumedPeriodEvents
import com.dynokore.gst.tenancy.adapters.calendar.NonBusinessDayPolicyHandler
import com.dynokore.gst.tenancy.adapters.calendar.PeriodFilingStatusHandler
import com.dynokore.gst.tenancy.adapters.events.PeriodEventsOutbox
import com.dynokore.gst.tenancy.adapters.http.PeriodListCursor
import com.dynokore.gst.tenancy.adapters.persistence.JooqFilingFactStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqPeriodExtensionStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqStagedCalendarPacks
import com.dynokore.gst.tenancy.adapters.policy.TenancyVariationPoints
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.period.CalendarPackReplica
import com.dynokore.gst.tenancy.application.period.CalendarPackSource
import com.dynokore.gst.tenancy.application.period.DueDateRecomputation
import com.dynokore.gst.tenancy.application.period.FilingFactStore
import com.dynokore.gst.tenancy.application.period.FrequencyHistoryStore
import com.dynokore.gst.tenancy.application.period.ListPeriodRecords
import com.dynokore.gst.tenancy.application.period.PackActivationRecompute
import com.dynokore.gst.tenancy.application.period.PackDeltaPreview
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.application.period.PeriodEventPublisher
import com.dynokore.gst.tenancy.application.period.PeriodExtensionStore
import com.dynokore.gst.tenancy.application.period.PeriodPlanner
import com.dynokore.gst.tenancy.application.period.PeriodRecordStore
import com.dynokore.gst.tenancy.application.period.RecordExtension
import com.dynokore.gst.tenancy.application.period.StagedCalendarPacks
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * TPL-18: the extensions, the period read, the recomputation of due dates and their consumers (INV-T-4930). The
 * consumers are inbox consumers of their own on `gst.platform.policy-changed.v1` (VP-62) and
 * `gst.submission.period-filing-status-changed.v1`; the calendar pack activation recomputes through
 * [PackActivationRecompute]. Every permission is decided per request by [PeriodAccess].
 */
@Configuration(proxyBeanMethods = false)
@Suppress("TooManyFunctions") // one bean per collaborator, as in PeriodUseCases
class PeriodCalendarUseCases {
    /** The cursor of `listPeriodRecords` (INV-Q-16673): the key is read through the secret store at every use. */
    @Bean
    fun periodListCursor(secrets: SecretStore): PeriodListCursor = PeriodListCursor(secrets)

    @Bean
    fun periodExtensionStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): PeriodExtensionStore = JooqPeriodExtensionStore(dsl, tenants)

    @Bean
    fun filingFactStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): FilingFactStore = JooqFilingFactStore(dsl, tenants)

    @Bean
    fun stagedCalendarPacks(
        dsl: DSLContext,
        decoder: CalendarPackDecoder,
        mapper: ObjectMapper,
    ): StagedCalendarPacks = JooqStagedCalendarPacks(dsl, decoder, mapper)

    @Bean
    fun periodEventPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
    ): PeriodEventPublisher = PeriodEventsOutbox(outbox, mapper, tenants)

    @Bean
    @Suppress("LongParameterList") // one provider per collaborator of the use case
    fun recordExtension(
        tx: TransactionRunner,
        structure: StructureStore,
        access: PeriodAccess,
        caller: CallerSource,
        periods: PeriodRecordStore,
        extensions: PeriodExtensionStore,
        publisher: PeriodEventPublisher,
        recomputation: DueDateRecomputation,
    ): RecordExtension = RecordExtension(tx, structure, access, caller, periods, extensions, publisher, recomputation)

    @Bean
    fun listPeriodRecords(
        tx: TransactionRunner,
        structure: StructureStore,
        periods: PeriodRecordStore,
        extensions: PeriodExtensionStore,
        facts: FilingFactStore,
    ): ListPeriodRecords = ListPeriodRecords(tx, structure, periods, extensions, facts)

    @Bean
    @Suppress("LongParameterList") // one provider per collaborator of the use case
    fun dueDateRecomputation(
        structure: StructureStore,
        planner: PeriodPlanner,
        history: FrequencyHistoryStore,
        periods: PeriodRecordStore,
        extensions: PeriodExtensionStore,
        facts: FilingFactStore,
        publisher: PeriodEventPublisher,
    ): DueDateRecomputation = DueDateRecomputation(structure, planner, history, periods, extensions, facts, publisher)

    @Bean
    fun packActivationRecompute(
        replica: CalendarPackReplica,
        periods: PeriodRecordStore,
        recomputation: DueDateRecomputation,
        staged: StagedCalendarPacks,
        packs: ObjectProvider<CalendarPackSource>,
    ): PackActivationRecompute =
        PackActivationRecompute(replica, periods, recomputation, staged, selectedCalendarSource(packs))

    @Bean
    fun packDeltaPreview(
        tx: TransactionRunner,
        structure: StructureStore,
        periods: PeriodRecordStore,
        recomputation: DueDateRecomputation,
        staged: StagedCalendarPacks,
    ): PackDeltaPreview = PackDeltaPreview(tx, structure, periods, recomputation, staged)

    @Bean
    fun nonBusinessDayPolicyConsumer(
        recomputation: DueDateRecomputation,
        mapper: ObjectMapper,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            ConsumedPeriodEvents.POLICY_CHANGED_CONSUMER,
            ConsumedPeriodEvents.POLICY_CHANGED_TOPIC,
            NonBusinessDayPolicyHandler(recomputation, mapper, TenancyVariationPoints.NON_BUSINESS_DAY_RULE.value),
        )

    @Bean
    fun periodFilingStatusConsumer(
        facts: FilingFactStore,
        mapper: ObjectMapper,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            ConsumedPeriodEvents.FILING_STATUS_CONSUMER,
            ConsumedPeriodEvents.FILING_STATUS_TOPIC,
            PeriodFilingStatusHandler(facts, mapper),
        )
}
