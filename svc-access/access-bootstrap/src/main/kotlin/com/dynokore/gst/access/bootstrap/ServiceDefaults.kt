package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AccessEventCatalogue
import com.dynokore.gst.access.adapters.http.GrantManagerCheck
import com.dynokore.gst.access.adapters.http.InstallationRole
import com.dynokore.gst.access.adapters.http.LiveGrantManagerCheck
import com.dynokore.gst.access.adapters.http.LiveTenantGrants
import com.dynokore.gst.access.adapters.http.TenantPolicyRoleResolver
import com.dynokore.gst.access.adapters.persistence.jooq.Access
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.principal.MachinePermissionSets
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.Schema
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/** The service's schema, its event catalogue and the fail-closed defaults a deployment or a test replaces. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InstallationRoleProperties::class)
class ServiceDefaults {
    @Bean
    fun accessSchema(): Schema = Access.ACCESS

    @Bean
    fun eventCatalogue(): EventCatalogue = AccessEventCatalogue.load()

    /**
     * The role-by-permission table of the gst-catalogue for the installation's deployment form (INV-T-4935, INV-Q-7979
     * A). A role or permission key the loader cannot map stops the start-up (fail closed).
     */
    @Bean
    @ConditionalOnMissingBean
    fun permissionMatrix(properties: GstProperties): PermissionMatrix =
        PermissionMatrixLoader.load(catalogueForm(properties))

    /** The roles of the installation's form: a private installation has no `platform.support` (INV-REQ-888). */
    @Bean
    @ConditionalOnMissingBean
    fun roleCatalogue(properties: GstProperties): RoleCatalogue =
        PermissionMatrixLoader.roleCatalogue(catalogueForm(properties))

    private fun catalogueForm(properties: GstProperties): DeploymentForm = DeploymentForm.valueOf(properties.form.name)

    /**
     * The caller check of grantRole, revokeGrant and the sole-operator operations: a live Tenant-scope grant whose role
     * has `user.manage` PERMITTED in the matrix, read from this service's own grant table.
     */
    @Bean
    @ConditionalOnMissingBean
    fun grantManagerCheck(
        tenants: TenantContext,
        live: LiveTenantGrants,
        matrix: PermissionMatrix,
    ): GrantManagerCheck = LiveGrantManagerCheck(tenants, live, matrix)

    @Bean
    @ConditionalOnMissingBean
    fun liveTenantGrants(
        tenants: TenantContext,
        tx: TransactionRunner,
        grants: GrantRepository,
        clock: Clock,
    ) = LiveTenantGrants(tenants, tx, grants, clock)

    /**
     * The roles `setPolicy` of service-runtime asks for: the live tenant grants at the TENANT level, the configured
     * `access.installation-roles` at the INSTALLATION level (INV-REQ-2756 item 1, INV-Q-8789 A). The property has no
     * default: start-up fails when it is absent or an entry is invalid.
     */
    @Bean
    @ConditionalOnMissingBean
    fun policyRoleResolver(
        live: LiveTenantGrants,
        installation: InstallationRoleProperties,
    ): PolicyRoleResolver = TenantPolicyRoleResolver(live, installation.entries())

    /**
     * The fixed permission set of each of the six machine principal types (INV-REQ-584, INV-Q-8779 A). Built from the
     * rows of the contract's data file as the service enforces them; a type without a row stops the start-up.
     */
    @Bean
    @ConditionalOnMissingBean
    fun machinePermissionSets(): MachinePermissionSets = MachinePermissionSets.STANDARD

    /**
     * The live consent lookup behind a consent-derived scope: reads the consent record at every question, so a
     * withdrawal shows at the next check (INV-REQ-865). A failed read refuses.
     */
    @Bean
    @ConditionalOnMissingBean
    fun consentCheck(
        tx: TransactionRunner,
        consents: ConsentRepository,
        clock: Clock,
    ): ConsentCheck =
        ConsentCheck { client ->
            // the policy asks after the grants are read, so the question runs in its own (or the open) transaction
            runCatching {
                val today = clock.instant().atZone(BUSINESS_ZONE).toLocalDate()
                tx.inTransaction { consents.findLiveForClient(client)?.isLiveOn(today) == true }
            }.getOrDefault(false)
        }

    private companion object {
        val BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Singapore")
    }
}

/**
 * The subjects that hold `platform.engineer` (SaaS) or `deployment.operator` (private) at installation scope, set per
 * installation: a list of entries with a `subject` and a `role`. No default; the empty list is valid.
 */
@ConfigurationProperties(prefix = "access")
class InstallationRoleProperties(
    val installationRoles: List<Entry>?,
) {
    class Entry(
        val subject: String?,
        val role: String?,
    )

    /** The validated entries; throws when the property is absent or an entry is invalid. */
    fun entries(): List<InstallationRole> {
        val listed = requireNotNull(installationRoles) { "access.installation-roles is required and has no default" }
        return listed.map { InstallationRole.of(it.subject, it.role) }
    }
}
