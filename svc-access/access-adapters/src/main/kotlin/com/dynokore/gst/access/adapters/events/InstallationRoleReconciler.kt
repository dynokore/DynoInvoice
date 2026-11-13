package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.http.InstallationRole
import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.gst.access.adapters.persistence.InstallationRoleGrantRow
import com.dynokore.gst.access.adapters.persistence.JooqInstallationRoleGrants
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat

/** The reference of the grant of one configured entry (INV-T-9235): deterministic and at most 64 characters. */
object InstallationGrantRef {
    private const val PREFIX = "inst-"
    private const val HEX_CHARS = 32

    /** `inst-` and the first 32 lower-case hex characters of SHA-256 over the role, a NUL and the subject. */
    fun of(
        role: String,
        subject: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$role\u0000$subject".toByteArray(Charsets.UTF_8))
        return PREFIX + HexFormat.of().formatHex(digest).take(HEX_CHARS)
    }
}

/**
 * Publishes the configured `access.installation-roles` as INSTALLATION role grants (INV-T-9235, INV-Q-8972 A,
 * INV-Q-9229 A): at start it compares the entries with the rows of `installation_role_grant` and, in one transaction
 * bound to the platform tenant, writes the difference and appends one AuthorityChanged event per change. An entry
 * with no ACTIVE row becomes ACTIVE at a new authority version; an ACTIVE row whose entry is gone becomes REMOVED at a
 * new version; an entry equal to an ACTIVE row writes nothing. The event is the platform event of the catalogue, under
 * `TenantId.PLATFORM` and `EntityRef.PLATFORM`, keyed on the subject.
 */
@Suppress("LongParameterList") // one collaborator per step of the reconcile transaction
class InstallationRoleReconciler(
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val grants: JooqInstallationRoleGrants,
    private val outbox: OutboxWriter,
    private val payloads: AuthorityChangedPayloads,
    private val clock: Clock,
    private val head: AuthorityHeadStore,
) {
    /** Reconciles [entries]; returns the rows written, in the order of their events. */
    fun reconcile(entries: List<InstallationRole>): List<InstallationRoleGrantRow> =
        platform.bindPlatform(ACTOR) {
            tx.inTransaction {
                val wanted = entries.associateBy { InstallationGrantRef.of(it.role.wire, it.subject) }.toSortedMap()
                val held = grants.all().associateBy { it.grantRef }
                val now = clock.instant().atOffset(java.time.ZoneOffset.UTC)
                val added =
                    wanted
                        .filter { (ref, _) -> held[ref]?.active != true }
                        .map { (ref, entry) ->
                            InstallationRoleGrantRow(ref, entry.subject, entry.role.wire, true, 0, now)
                        }
                val removed =
                    held.values
                        .filter { it.active && it.grantRef !in wanted }
                        .map { it.copy(active = false, updatedAt = now) }
                (added + removed).sortedBy { it.grantRef }.map { change ->
                    val versioned = change.copy(authorityVersion = grants.nextAuthorityVersion())
                    grants.save(versioned)
                    append(versioned)
                    head.advance(versioned.authorityVersion)
                    versioned
                }
            }
        }

    private fun append(row: InstallationRoleGrantRow) {
        val context = EventContexts.forAggregate(TenantId.PLATFORM, EntityRef.PLATFORM, row.subject)
        val json = payloads.installationGrant(row).toString()
        outbox.append(listOf(OutboundEvent(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, json)), context)
    }

    companion object {
        /** The workload principal of the reconciliation (ARC-21). */
        const val ACTOR = "svc:access-svc/job:installation-role-grants"
    }
}
