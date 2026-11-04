package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.breakglass.SecureRandomSealingCredentials
import com.dynokore.gst.access.adapters.breakglass.VariationPointBreakGlassSettings
import com.dynokore.gst.access.adapters.events.BreakGlassAlertOutbox
import com.dynokore.gst.access.adapters.http.BreakGlassSealing
import com.dynokore.gst.access.adapters.http.ResealPermissionCheck
import com.dynokore.gst.access.adapters.http.ResolvedResealPermissionCheck
import com.dynokore.gst.access.adapters.persistence.JooqBreakGlassAccounts
import com.dynokore.gst.access.adapters.persistence.JooqBreakGlassApprovals
import com.dynokore.gst.access.adapters.persistence.JooqBreakGlassSessionView
import com.dynokore.gst.access.adapters.persistence.JooqBreakGlassSessions
import com.dynokore.gst.access.adapters.persistence.JooqGrantRepository
import com.dynokore.gst.access.adapters.persistence.JooqIdpGroupMappings
import com.dynokore.gst.access.adapters.persistence.JooqMappedGrants
import com.dynokore.gst.access.adapters.persistence.JooqSignInAttempts
import com.dynokore.gst.access.application.breakglass.ApproveBreakGlassUnlockUseCase
import com.dynokore.gst.access.application.breakglass.BreakGlassAccounts
import com.dynokore.gst.access.application.breakglass.BreakGlassAlerts
import com.dynokore.gst.access.application.breakglass.BreakGlassApprovals
import com.dynokore.gst.access.application.breakglass.BreakGlassEffects
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.application.breakglass.BreakGlassSettings
import com.dynokore.gst.access.application.breakglass.MAX_APPROVAL_WINDOW
import com.dynokore.gst.access.application.breakglass.RecordBreakGlassActionUseCase
import com.dynokore.gst.access.application.breakglass.ResealBreakGlassUseCase
import com.dynokore.gst.access.application.breakglass.SealedAccountKeeper
import com.dynokore.gst.access.application.breakglass.SealingCredentials
import com.dynokore.gst.access.application.breakglass.SignInAttempts
import com.dynokore.gst.access.application.breakglass.SignInWithBackoffUseCase
import com.dynokore.gst.access.application.breakglass.UnlockBreakGlassUseCase
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.application.idp.IdentityProviderAvailability
import com.dynokore.gst.access.application.idp.IdpGroupCollaborators
import com.dynokore.gst.access.application.idp.IdpGroupMappings
import com.dynokore.gst.access.application.idp.IdpProvisioningEffects
import com.dynokore.gst.access.application.idp.MapIdpGroupUseCase
import com.dynokore.gst.access.application.idp.MappedGrants
import com.dynokore.gst.access.application.idp.SyncIdpGroupsUseCase
import com.dynokore.gst.access.application.idp.UnmapIdpGroupUseCase
import com.dynokore.gst.access.application.separation.ConflictingRolePairs
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionView
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeParseException

/**
 * TPL-18, continued: the use cases of the directory-group mappings (INV-REQ-892, INV-REQ-1263) and of break-glass
 * (INV-REQ-896, INV-REQ-897, INV-REQ-1269), constructed here so that [UseCases] stays within the function limit.
 */
@Configuration(proxyBeanMethods = false)
class IdpAndBreakGlassUseCases {
    @Bean
    fun idpGroupMappings(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): IdpGroupMappings = JooqIdpGroupMappings(dsl, tenants, clock)

    @Bean
    fun idpGroupCollaborators(
        tx: TransactionRunner,
        mappings: IdpGroupMappings,
        members: DirectoryGroupMembers,
        clock: Clock,
    ) = IdpGroupCollaborators(tx, mappings, members, clock)

    @Bean
    fun mappedGrants(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
        consent: ConsentCheck,
    ): MappedGrants = JooqMappedGrants(dsl, JooqGrantRepository(dsl, tenants, clock, consent))

    @Bean
    fun idpProvisioningEffects(
        publisher: AuthorityChangePublisher,
        audit: GrantAudit,
        catalogue: RoleCatalogue,
        mapped: MappedGrants,
        conflictingPairs: ConflictingRolePairs,
    ) = IdpProvisioningEffects(publisher, audit, catalogue, mapped, conflictingPairs)

    @Bean
    fun mapIdpGroupUseCase(
        shared: IdpGroupCollaborators,
        dependencies: GrantDependencies,
        effects: IdpProvisioningEffects,
    ) = MapIdpGroupUseCase(shared, dependencies.grants, dependencies.directory, dependencies.matrix, effects)

    @Bean
    fun unmapIdpGroupUseCase(
        shared: IdpGroupCollaborators,
        dependencies: GrantDependencies,
        effects: IdpProvisioningEffects,
    ) = UnmapIdpGroupUseCase(shared, dependencies.grants, dependencies.directory, dependencies.matrix, effects)

    @Bean
    fun syncIdpGroupsUseCase(
        shared: IdpGroupCollaborators,
        dependencies: GrantDependencies,
        effects: IdpProvisioningEffects,
    ) = SyncIdpGroupsUseCase(shared, dependencies.grants, dependencies.directory, dependencies.matrix, effects)
}

/**
 * `access.break-glass.approval-window` (INV-Q-9109 A): how long an approval of an unlock counts. Default `PT15M`; a
 * value above `PT15M`, zero, negative or unparsable stops the start-up with a message that names the setting.
 */
@ConfigurationProperties(prefix = "access.break-glass")
class BreakGlassApprovalProperties(
    val approvalWindow: String?,
) {
    /** The validated window; throws [IllegalArgumentException] naming the setting. */
    fun window(): Duration {
        val text = approvalWindow ?: return MAX_APPROVAL_WINDOW
        val parsed =
            try {
                Duration.parse(text)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("$SETTING must be an ISO-8601 duration such as PT15M, got '$text'", e)
            }
        require(!parsed.isZero && !parsed.isNegative && parsed <= MAX_APPROVAL_WINDOW) {
            "$SETTING must be above zero and at most PT15M, got '$text'"
        }
        return parsed
    }

    private companion object {
        const val SETTING = "access.break-glass.approval-window"
    }
}

/** The validated approval window as a bean; building it stops the start-up on an invalid value. */
class BreakGlassApprovalWindow(
    val value: Duration,
)

/** TPL-18, continued: the use cases of break-glass (INV-REQ-896, INV-REQ-897, INV-REQ-1269). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BreakGlassApprovalProperties::class)
class BreakGlassUseCases {
    @Bean
    fun breakGlassApprovalWindow(properties: BreakGlassApprovalProperties) =
        BreakGlassApprovalWindow(properties.window())

    @Bean
    fun breakGlassSessions(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): BreakGlassSessions = JooqBreakGlassSessions(dsl, tenants, clock)

    /** Replaces the default view of the restricted mode: access-svc reads its own table (INV-Q-9298 B). */
    @Bean
    fun breakGlassSessionView(
        dsl: DSLContext,
        tenants: TenantContext,
        tx: TransactionRunner,
    ): BreakGlassSessionView = JooqBreakGlassSessionView(dsl, tenants, tx)

    @Bean
    fun breakGlassAlerts(
        outbox: OutboxWriter,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ): BreakGlassAlerts = BreakGlassAlertOutbox(outbox, tenants, mapper)

    /** VP-08 as `setPolicy` of service-runtime stores it and the config-resolver resolves it (INV-REQ-1269). */
    @Bean
    fun breakGlassSettings(
        reader: PolicyReader,
        tenants: TenantContext,
        clock: Clock,
        mapper: ObjectMapper,
    ): BreakGlassSettings = VariationPointBreakGlassSettings(reader, tenants, clock, mapper)

    @Bean
    fun breakGlassEffects(
        alerts: BreakGlassAlerts,
        audit: GrantAudit,
    ) = BreakGlassEffects(alerts, audit)

    @Bean
    @Suppress("LongParameterList") // one collaborator per port of the use case, as its constructor
    fun unlockBreakGlassUseCase(
        dependencies: GrantDependencies,
        sessions: BreakGlassSessions,
        settings: BreakGlassSettings,
        effects: BreakGlassEffects,
        clock: Clock,
        availability: IdentityProviderAvailability,
        approvals: BreakGlassApprovals,
        window: BreakGlassApprovalWindow,
    ) = UnlockBreakGlassUseCase(
        dependencies.tx,
        sessions,
        dependencies.grants,
        settings,
        effects,
        clock,
        availability,
        approvals,
        window.value,
    )

    @Bean
    fun breakGlassApprovals(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): BreakGlassApprovals = JooqBreakGlassApprovals(dsl, tenants, clock)

    @Bean
    @Suppress("LongParameterList") // one collaborator per port of the use case, as its constructor
    fun approveBreakGlassUnlockUseCase(
        dependencies: GrantDependencies,
        approvals: BreakGlassApprovals,
        settings: BreakGlassSettings,
        matrix: PermissionMatrix,
        audit: GrantAudit,
        clock: Clock,
        window: BreakGlassApprovalWindow,
    ) = ApproveBreakGlassUnlockUseCase(
        dependencies.tx,
        approvals,
        dependencies.grants,
        settings,
        matrix,
        audit,
        clock,
        window.value,
    )

    @Bean
    fun recordBreakGlassActionUseCase(
        dependencies: GrantDependencies,
        sessions: BreakGlassSessions,
        effects: BreakGlassEffects,
        clock: Clock,
    ) = RecordBreakGlassActionUseCase(dependencies.tx, sessions, effects, clock)
}

/** TPL-18, continued: the sealed break-glass account, `signInBreakGlass` and `resealBreakGlass` (INV-T-8853). */
@Configuration(proxyBeanMethods = false)
class SealedAccountUseCases {
    @Bean
    fun breakGlassAccounts(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): BreakGlassAccounts = JooqBreakGlassAccounts(dsl, tenants, clock)

    /** New sealing credentials from the platform secure random; a test replaces the bean with a double. */
    @Bean
    @ConditionalOnMissingBean
    fun sealingCredentials(): SealingCredentials = SecureRandomSealingCredentials()

    @Bean
    fun signInAttempts(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): SignInAttempts = JooqSignInAttempts(dsl, tenants, clock)

    @Bean
    fun sealedAccountKeeper(
        accounts: BreakGlassAccounts,
        credentials: SealingCredentials,
    ) = SealedAccountKeeper(accounts, credentials)

    @Suppress("LongParameterList") // one collaborator per port of the use case, as its constructor
    @Bean
    fun signInBreakGlassUseCase(
        dependencies: GrantDependencies,
        sessions: BreakGlassSessions,
        keeper: SealedAccountKeeper,
        attempts: SignInAttempts,
        effects: BreakGlassEffects,
        clock: Clock,
    ) = SignInWithBackoffUseCase(dependencies.tx, sessions, keeper, attempts, effects, clock)

    @Bean
    fun resealBreakGlassUseCase(
        dependencies: GrantDependencies,
        sessions: BreakGlassSessions,
        keeper: SealedAccountKeeper,
        audit: GrantAudit,
        clock: Clock,
    ) = ResealBreakGlassUseCase(dependencies.tx, sessions, keeper, audit, clock)

    @Bean
    fun resealPermissionCheck(
        tenants: TenantContext,
        roles: PolicyRoleResolver,
        matrix: PermissionMatrix,
    ): ResealPermissionCheck = ResolvedResealPermissionCheck(tenants, roles, matrix)

    @Bean
    fun breakGlassSealing(
        signIn: SignInWithBackoffUseCase,
        reseal: ResealBreakGlassUseCase,
        permission: ResealPermissionCheck,
    ) = BreakGlassSealing(signIn, reseal, permission)
}
