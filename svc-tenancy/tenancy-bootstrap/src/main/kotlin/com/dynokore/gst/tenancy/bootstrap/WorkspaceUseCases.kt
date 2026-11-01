package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.JooqSampleApprovals
import com.dynokore.gst.tenancy.adapters.policy.PolicyProductionSamplePolicy
import com.dynokore.gst.tenancy.adapters.workspace.ReplicaTenantAuthority
import com.dynokore.gst.tenancy.application.tenant.WorkspaceStore
import com.dynokore.gst.tenancy.application.workspace.ArchiveWorkspace
import com.dynokore.gst.tenancy.application.workspace.CreateWorkspace
import com.dynokore.gst.tenancy.application.workspace.CredentialBinder
import com.dynokore.gst.tenancy.application.workspace.LoadCredential
import com.dynokore.gst.tenancy.application.workspace.ProductionSamplePolicy
import com.dynokore.gst.tenancy.application.workspace.SampleApprovals
import com.dynokore.gst.tenancy.application.workspace.SampleCopies
import com.dynokore.gst.tenancy.application.workspace.SampleCopyPort
import com.dynokore.gst.tenancy.application.workspace.SampleRowSource
import com.dynokore.gst.tenancy.application.workspace.SampleTransfer
import com.dynokore.gst.tenancy.application.workspace.ServiceEnvironment
import com.dynokore.gst.tenancy.application.workspace.TenantAuthority
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import org.springframework.core.env.Environment as SpringEnvironment

/** TPL-18: workspaces, the environment of this service, credential isolation and the production-sample copy. */
@Configuration(proxyBeanMethods = false)
class WorkspaceUseCases {
    /**
     * INV-REQ-502: the environment is explicit configuration, `inv.tenancy.environment`, and there is no default: a
     * start without it, or with a value that is neither PRODUCTION nor SANDBOX, fails here, before any
     * request is served.
     */
    @Bean
    fun serviceEnvironment(environment: SpringEnvironment): ServiceEnvironment {
        val configured = environment.getProperty(ENVIRONMENT_PROPERTY)
        val selected =
            checkNotNull(Environment.ofTag(configured)) {
                "$ENVIRONMENT_PROPERTY must be PRODUCTION or SANDBOX, no default (found ${configured ?: "none"})"
            }
        return ServiceEnvironment(selected)
    }

    @Bean
    fun createWorkspace(
        tenants: TenantContext,
        tx: TransactionRunner,
        store: WorkspaceStore,
    ): CreateWorkspace = CreateWorkspace(tenants, tx, store)

    @Bean
    fun archiveWorkspace(
        tx: TransactionRunner,
        store: WorkspaceStore,
    ): ArchiveWorkspace = ArchiveWorkspace(tx, store)

    /**
     * The credential store belongs to the Access Point service; no binder is bound yet, so every accepted
     * load fails closed.
     */
    @Bean
    fun credentialBinder(): CredentialBinder = CredentialBinder { _, _ -> error("no credential binder is bound") }

    @Bean
    fun loadCredential(
        tx: TransactionRunner,
        store: WorkspaceStore,
        environment: ServiceEnvironment,
        binder: CredentialBinder,
    ): LoadCredential = LoadCredential(tx, store, environment, binder)

    @Bean
    fun productionSamplePolicy(
        policy: PolicyReader,
        tenants: TenantContext,
    ): ProductionSamplePolicy =
        PolicyProductionSamplePolicy(policy, tenants) { LocalDate.now(Clock.system(ZoneId.of("Asia/Singapore"))) }

    @Bean
    fun sampleApprovals(
        dsl: DSLContext,
        tenants: TenantContext,
    ): SampleApprovals = JooqSampleApprovals(dsl, tenants)

    /**
     * The copy between workspaces is bound in Wave 3 (coordinator ruling INV-Q-4900); until then a copy
     * fails closed.
     */
    @Bean
    fun sampleCopyPort(): SampleCopyPort = SampleCopyPort { _, _, _ -> error("no sample copy port is bound") }

    /** The sample-copy permission keys are decided by the authority replica's `decideTenant` (INV-Q-11901 A). */
    @Bean
    @ConditionalOnMissingBean(TenantAuthority::class)
    fun tenantAuthority(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
    ): TenantAuthority = ReplicaTenantAuthority(replica.getIfAvailable(), tenants)

    /** The rows of a production sample belong to the service that owns them, bound in Wave 3; a copy fails closed. */
    @Bean
    @ConditionalOnMissingBean(SampleRowSource::class)
    fun sampleRowSource(): SampleRowSource = SampleRowSource { _ -> error("no sample row source is bound") }

    @Bean
    @Suppress("LongParameterList")
    fun sampleCopies(
        tx: TransactionRunner,
        store: WorkspaceStore,
        policy: ProductionSamplePolicy,
        approvals: SampleApprovals,
        authority: TenantAuthority,
        source: SampleRowSource,
        port: SampleCopyPort,
    ): SampleCopies = SampleCopies(tx, store, policy, approvals, authority, SampleTransfer(source, port))

    private companion object {
        const val ENVIRONMENT_PROPERTY = "inv.tenancy.environment"
    }
}
