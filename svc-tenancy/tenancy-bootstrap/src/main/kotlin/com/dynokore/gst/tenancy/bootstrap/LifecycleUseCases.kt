package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.JooqEntityLifecycleStore
import com.dynokore.gst.tenancy.application.certification.CertificationStore
import com.dynokore.gst.tenancy.application.confirmation.SecondConfirmation
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.ElectionGate
import com.dynokore.gst.tenancy.application.lifecycle.DeregisterEntity
import com.dynokore.gst.tenancy.application.lifecycle.EntityLifecycleStore
import com.dynokore.gst.tenancy.application.lifecycle.FinalPeriod
import com.dynokore.gst.tenancy.application.lifecycle.GoLive
import com.dynokore.gst.tenancy.application.lifecycle.SuspendEntity
import com.dynokore.gst.tenancy.application.lifecycle.TriggerCoverage
import com.dynokore.gst.tenancy.application.lifecycle.UnboundTriggerCoverage
import com.dynokore.gst.tenancy.application.lifecycle.UnsubmittedOverrideLog
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.application.period.PeriodGeneration
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.LocalDate

/**
 * TPL-18: goLive, suspendEntity and deregisterEntity (INV-T-16414 use cases) over the boundary adapters (INV-T-4931).
 * The trigger-coverage port has no producer before Wave 3: its default binding answers "trigger coverage unknown", so
 * start-up succeeds in every profile (also `prod`) and goLive is refused (coordinator ruling INV-Q-4900). The
 * unsubmitted-documents port is bound in [OffboardingUseCases] and is unreadable until its owner binds it.
 */
@Configuration(proxyBeanMethods = false)
class LifecycleUseCases {
    @Bean
    fun jooqEntityLifecycleStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqEntityLifecycleStore = JooqEntityLifecycleStore(dsl, tenants)

    @Bean
    @ConditionalOnMissingBean(TriggerCoverage::class)
    fun triggerCoverage(): TriggerCoverage = UnboundTriggerCoverage()

    @Bean
    @Suppress("LongParameterList") // one parameter per collaborator of the use case
    fun goLive(
        tx: TransactionRunner,
        structure: StructureStore,
        authority: ActionAuthority,
        coverage: TriggerCoverage,
        certifications: CertificationStore,
        elections: ElectionGate,
        lifecycle: EntityLifecycleStore,
        statuses: EntityStatusStore,
        extras: ProfileExtras,
        publisher: EntityProfileChangePublisher,
        tenants: TenantContext,
        clock: ObjectProvider<Clock>,
    ): GoLive =
        GoLive(
            tx,
            structure,
            SecondConfirmation(authority),
            coverage,
            certifications,
            elections,
            lifecycle,
            statuses,
            extras,
            publisher,
            tenants::current,
        ) { today(clock) }

    @Bean
    @Suppress("LongParameterList") // one parameter per collaborator of the use case
    fun suspendEntity(
        tx: TransactionRunner,
        structure: StructureStore,
        lifecycle: EntityLifecycleStore,
        statuses: EntityStatusStore,
        extras: ProfileExtras,
        publisher: EntityProfileChangePublisher,
        tenants: TenantContext,
        clock: ObjectProvider<Clock>,
    ): SuspendEntity =
        SuspendEntity(tx, structure, lifecycle, statuses, extras, publisher, tenants::current) {
            today(clock)
        }

    @Bean
    @Suppress("LongParameterList") // one parameter per collaborator of the use case
    fun deregisterEntity(
        tx: TransactionRunner,
        structure: StructureStore,
        documents: UnsubmittedDocuments,
        generation: PeriodGeneration,
        overrides: UnsubmittedOverrideLog,
        lifecycle: EntityLifecycleStore,
        statuses: EntityStatusStore,
        extras: ProfileExtras,
        publisher: EntityProfileChangePublisher,
        tenants: TenantContext,
        clock: ObjectProvider<Clock>,
    ): DeregisterEntity =
        DeregisterEntity(
            tx,
            structure,
            documents,
            FinalPeriod.of(generation),
            overrides,
            lifecycle,
            statuses,
            extras,
            publisher,
            tenants::current,
        ) { today(clock) }

    private fun today(clock: ObjectProvider<Clock>): LocalDate = LocalDate.now(clock.getIfAvailable(Clock::systemUTC))
}
