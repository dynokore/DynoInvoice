package com.dynokore.gst.access.bootstrap
import com.dynokore.gst.access.adapters.audit.OutboxGrantAudit
import com.dynokore.gst.access.adapters.breakglass.BreakGlassPairConstraints
import com.dynokore.gst.access.adapters.breakglass.ValueConstraintsChain
import com.dynokore.gst.access.adapters.events.AuthorityChangedOutbox
import com.dynokore.gst.access.adapters.landing.RolePrecedenceConstraints
import com.dynokore.gst.access.adapters.landing.VariationPointRolePrecedence
import com.dynokore.gst.access.adapters.persistence.JooqGrantRepository
import com.dynokore.gst.access.adapters.persistence.JooqLandingChoices
import com.dynokore.gst.access.adapters.persistence.JooqSoleOperatorAcks
import com.dynokore.gst.access.adapters.structure.ReplicaEntityDirectory
import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.application.landing.LandingChoices
import com.dynokore.gst.access.application.landing.LandingScreens
import com.dynokore.gst.access.application.landing.RolePrecedence
import com.dynokore.gst.access.application.separation.RecordSoleOperatorUseCase
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.application.separation.WithdrawSoleOperatorUseCase
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18, continued: the use cases of the sole-operator acknowledgement (INV-REQ-891, INV-REQ-994) and of the landing
 * screen (INV-REQ-2756), constructed here so that [UseCases] stays within the function limit.
 */
@Configuration(proxyBeanMethods = false)
class SeparationAndLandingUseCases {
    @Bean
    fun soleOperatorAcks(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): SoleOperatorAcks = JooqSoleOperatorAcks(dsl, tenants, clock)

    @Bean
    fun recordSoleOperatorUseCase(
        tx: TransactionRunner,
        acks: SoleOperatorAcks,
        audit: GrantAudit,
    ) = RecordSoleOperatorUseCase(tx, acks, audit)

    @Bean
    fun withdrawSoleOperatorUseCase(
        tx: TransactionRunner,
        acks: SoleOperatorAcks,
        audit: GrantAudit,
    ) = WithdrawSoleOperatorUseCase(tx, acks, audit)

    @Bean
    fun landingChoices(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): LandingChoices = JooqLandingChoices(dsl, tenants, clock)

    /** VP-73 as `setPolicy` of service-runtime stores it and the config-resolver resolves it (INV-REQ-2756). */
    @Bean
    fun rolePrecedence(
        reader: PolicyReader,
        tenants: TenantContext,
        clock: Clock,
        mapper: ObjectMapper,
    ): RolePrecedence = VariationPointRolePrecedence(reader, tenants, clock, mapper)

    /**
     * The checks `setPolicy` asks of its owning service (INV-Q-3167): VP-73 and the pair of VP-08; no other variation
     * point is checked.
     */
    @Bean
    fun valueConstraints(
        catalogue: RoleCatalogue,
        mapper: ObjectMapper,
    ): ValueConstraints =
        ValueConstraintsChain(
            listOf(RolePrecedenceConstraints(catalogue, mapper), BreakGlassPairConstraints(catalogue, mapper)),
        )

    @Bean
    fun landingScreens(
        dependencies: GrantDependencies,
        choices: LandingChoices,
        precedence: RolePrecedence,
        clock: Clock,
    ) = LandingScreens(dependencies.tx, dependencies.grants, choices, precedence, clock)
}
