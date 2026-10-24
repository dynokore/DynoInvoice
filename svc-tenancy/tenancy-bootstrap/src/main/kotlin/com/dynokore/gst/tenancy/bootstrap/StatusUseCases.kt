package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ElectionValueConstraints
import com.dynokore.gst.tenancy.adapters.events.EntityProfileChangedOutbox
import com.dynokore.gst.tenancy.adapters.persistence.JooqPhaseAssessmentStore
import com.dynokore.gst.tenancy.adapters.policy.ImplementationDateConstraints
import com.dynokore.gst.tenancy.adapters.policy.PolicyGroupElectionReader
import com.dynokore.gst.tenancy.adapters.policy.PolicyImplementationBasisReader
import com.dynokore.gst.tenancy.application.status.AssessPhaseCommands
import com.dynokore.gst.tenancy.application.status.EntityLifecycleGuard
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusCommands
import com.dynokore.gst.tenancy.application.status.EntityStatusQueries
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.status.GroupElectionReader
import com.dynokore.gst.tenancy.application.status.ImplementationBasisReader
import com.dynokore.gst.tenancy.application.status.PhaseAssessmentStore
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
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
    fun phaseAssessmentStore(
        dsl: DSLContext,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ): PhaseAssessmentStore = JooqPhaseAssessmentStore(dsl, tenants, mapper)

    @Bean
    fun implementationBasisReader(
        policy: PolicyReader,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ): ImplementationBasisReader = PolicyImplementationBasisReader(policy, tenants, mapper)

    @Bean
    fun groupElectionReader(
        policy: PolicyReader,
        tenants: TenantContext,
    ): GroupElectionReader = PolicyGroupElectionReader(policy, tenants)

    /** config-resolver asks it for the parts of a VP-51 value only this service can check (INV-AC-2300). */
    @Bean
    fun implementationDateConstraints(
        store: StructureStore,
        assessments: PhaseAssessmentStore,
        mapper: ObjectMapper,
    ): ValueConstraints = ElectionValueConstraints(ImplementationDateConstraints(store, assessments, mapper))

    @Bean
    fun assessPhaseCommands(
        tx: TransactionRunner,
        store: StructureStore,
        assessments: PhaseAssessmentStore,
    ): AssessPhaseCommands = AssessPhaseCommands(tx, store, assessments)

    @Bean
    @Suppress("LongParameterList")
    fun entityStatusCommands(
        tx: TransactionRunner,
        store: StructureStore,
        statuses: EntityStatusStore,
        publisher: EntityProfileChangePublisher,
        assessments: PhaseAssessmentStore,
        implementation: ImplementationBasisReader,
        extras: ProfileExtras,
    ): EntityStatusCommands = EntityStatusCommands(tx, store, statuses, publisher, assessments, implementation, extras)

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
        elections: GroupElectionReader,
    ): EntityLifecycleGuard = EntityLifecycleGuard(tx, store, statuses, elections)
}
