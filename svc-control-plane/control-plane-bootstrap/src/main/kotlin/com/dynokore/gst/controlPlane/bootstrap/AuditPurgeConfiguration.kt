package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.audit.AuditPurgeStoreJooq
import com.dynokore.gst.controlPlane.adapters.audit.UndeterminedRetentionFloorSource
import com.dynokore.gst.controlPlane.application.audit.AuditEntryPurger
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeAuthority
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeFloorSource
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeIdSource
import com.dynokore.gst.controlPlane.application.audit.AuditPurgePermission
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditEntries
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/**
 * The audited purge of the operator's local audit entries (INV-T-3099, INV-Q-11446 A). The authority is the live
 * access-svc `authorise` binding of [OperatorPermissions] for the permission `audit-entry.purge` in the scope
 * `platform`; without `controlplane.access.base-url` that binding refuses everyone, so every call is NotPermitted
 * (fail closed, as [OperatorRolesConfiguration]). The floor source reports the floor undeterminable until
 * evidence-svc serves the figure (INV-T-3768).
 */
@Configuration(proxyBeanMethods = false)
class AuditPurgeConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun auditPurgeAuthority(permissions: OperatorPermissions): AuditPurgeAuthority =
        AuditPurgeAuthority { actor, permission, scope ->
            scope == AuditPurgePermission.SCOPE && permissions.holds(actor, permission)
        }

    @Bean
    @ConditionalOnMissingBean
    fun auditPurgeFloorSource(): AuditPurgeFloorSource = UndeterminedRetentionFloorSource()

    @Bean
    @ConditionalOnMissingBean
    fun auditPurgeIdSource(): AuditPurgeIdSource = AuditPurgeIdSource { UUID.randomUUID() }

    /**
     * The purger over `controlplane.purge_audit_entries`, in one transaction: the purge's own entry and the delete
     * commit together or not at all. The `AuditWriter` bean is the local writer under service-runtime's session
     * decorator, which stays an [AuditStager].
     */
    @Bean
    @ConditionalOnMissingBean
    fun auditEntryPurger(
        dsl: DSLContext,
        audit: AuditWriter,
        transactions: TransactionRunner,
    ): AuditEntryPurger {
        val stager =
            checkNotNull(audit as? AuditStager) { "the audit writer bean must be an AuditStager (local audit writer)" }
        val store = AuditPurgeStoreJooq(dsl, stager)
        return AuditEntryPurger { actor, cutOff, reason, context ->
            transactions.inTransaction { store.purge(actor, cutOff, reason, context) }
        }
    }

    @Bean
    fun purgeAuditEntries(
        authority: AuditPurgeAuthority,
        floorSource: AuditPurgeFloorSource,
        purger: AuditEntryPurger,
        clock: Clock,
        idSource: AuditPurgeIdSource,
    ): PurgeAuditEntries = PurgeAuditEntries(authority, floorSource, purger, clock, idSource)
}
