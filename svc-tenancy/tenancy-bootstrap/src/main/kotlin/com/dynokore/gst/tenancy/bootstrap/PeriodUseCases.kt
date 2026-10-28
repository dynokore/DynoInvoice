package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.FilingFrequencyChangedOutbox
import com.dynokore.gst.tenancy.adapters.persistence.JooqFrequencyHistoryStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqPeriodRecordStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqProposalStore
import com.dynokore.gst.tenancy.adapters.policy.PolicyNonBusinessDayRuleReader
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.period.CalendarPackSource
import com.dynokore.gst.tenancy.application.period.ConfirmPeriods
import com.dynokore.gst.tenancy.application.period.FilingFrequencyChangePublisher
import com.dynokore.gst.tenancy.application.period.FilingFrequencyCommands
import com.dynokore.gst.tenancy.application.period.FrequencyHistoryStore
import com.dynokore.gst.tenancy.application.period.NonBusinessDayRuleReader
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.application.period.PeriodGeneration
import com.dynokore.gst.tenancy.application.period.PeriodPlanner
import com.dynokore.gst.tenancy.application.period.PeriodRecordStore
import com.dynokore.gst.tenancy.application.period.PeriodStores
import com.dynokore.gst.tenancy.application.period.ProposalStore
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * TPL-18: the filing-frequency and period use cases (INV-T-4929), constructed here and nowhere else.
 *
 * The port `CalendarPackSource` has no adapter in the production profile and fails closed (INV-Q-9725 A): it answers
 * no pack, so every operation that needs one is refused 422 POL-014 (INV-T-4930 binds the port). The permission checks
 * use the caller and authority ports of INV-T-4927 (INV-Q-10127 A); with no replica every check answers false, so both
 * operations are refused 403 SRT-004. Start-up succeeds either way.
 */
@Configuration(proxyBeanMethods = false)
class PeriodUseCases {
    @Bean
    fun frequencyHistoryStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): FrequencyHistoryStore = JooqFrequencyHistoryStore(dsl, tenants)

    @Bean
    fun proposalStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): ProposalStore = JooqProposalStore(dsl, tenants)

    @Bean
    fun periodRecordStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): PeriodRecordStore = JooqPeriodRecordStore(dsl, tenants)

    @Bean
    fun nonBusinessDayRuleReader(
        policy: PolicyReader,
        tenants: TenantContext,
    ): NonBusinessDayRuleReader = PolicyNonBusinessDayRuleReader(policy, tenants)

    @Bean
    fun filingFrequencyChangePublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
    ): FilingFrequencyChangePublisher = FilingFrequencyChangedOutbox(outbox, mapper, tenants)

    @Bean
    fun periodStores(
        history: FrequencyHistoryStore,
        proposals: ProposalStore,
        periods: PeriodRecordStore,
    ): PeriodStores = PeriodStores(history, proposals, periods)

    @Bean
    fun periodPlanner(
        statuses: EntityStatusStore,
        packs: ObjectProvider<CalendarPackSource>,
        rules: NonBusinessDayRuleReader,
    ): PeriodPlanner = PeriodPlanner(statuses, packs.getIfAvailable { CalendarPackSource { null } }, rules)

    @Bean
    fun periodGeneration(
        tx: TransactionRunner,
        structure: StructureStore,
        planner: PeriodPlanner,
        history: FrequencyHistoryStore,
        periods: PeriodRecordStore,
    ): PeriodGeneration = PeriodGeneration(tx, structure, planner, history, periods)

    @Bean
    @Suppress("LongParameterList") // one provider per collaborator of the use case, as in ElectionUseCases
    fun filingFrequencyCommands(
        tx: TransactionRunner,
        structure: StructureStore,
        planner: PeriodPlanner,
        generation: PeriodGeneration,
        access: PeriodAccess,
        stores: PeriodStores,
    ): FilingFrequencyCommands = FilingFrequencyCommands(tx, structure, planner, generation, access, stores)

    @Bean
    fun periodAccess(
        authority: ActionAuthority,
        caller: CallerSource,
    ): PeriodAccess = PeriodAccess(authority, caller)

    @Bean
    fun confirmPeriods(
        tx: TransactionRunner,
        structure: StructureStore,
        access: PeriodAccess,
        stores: PeriodStores,
        publisher: FilingFrequencyChangePublisher,
    ): ConfirmPeriods = ConfirmPeriods(tx, structure, access, stores, publisher)
}
