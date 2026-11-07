package com.dynokore.gst.tenancy.application.election

import com.dynokore.gst.tenancy.application.structure.EntityStore
import com.dynokore.gst.tenancy.domain.ElectionKeys
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.LocalDate

/** One stored exempt-purchase election (INV-REQ-369); [recordedBy] is the acting user id, never a role. */
data class ExemptPurchaseElectionRow(
    val tenant: TenantId,
    val entityRef: String,
    val effectiveFrom: LocalDate,
    val taxCodes: List<String>,
    val basis: String,
    val recordedBy: String,
)

/** The exempt-purchase elections of the bound tenant, effective-dated and append-only. */
interface ExemptPurchaseElectionStore {
    /** The rows of [entityRef], oldest first. */
    fun history(entityRef: String): List<ExemptPurchaseElectionRow>

    fun insert(row: ExemptPurchaseElectionRow)
}

sealed interface ExemptPurchaseOutcome {
    /** [repeated] is true when identical codes and basis were stored for the same date and nothing was written. */
    data class Done(
        val row: ExemptPurchaseElectionRow,
        val repeated: Boolean = false,
    ) : ExemptPurchaseOutcome

    /** 404: no such entity. */
    data object NotFound : ExemptPurchaseOutcome

    /** 400: codes or basis refused by the domain check; nothing is stored. */
    data class Invalid(
        val reason: String,
    ) : ExemptPurchaseOutcome

    /** 403: the caller does not hold the permission, or it cannot be proven; nothing is stored. */
    data class Forbidden(
        val reason: String,
    ) : ExemptPurchaseOutcome
}

/**
 * Records the exempt-purchase election of an entity (INV-REQ-369 behavior 1 to 3): the caller must hold
 * `entity-config.manage` on the entity (decided by the authority replica, INV-Q-10027; no role is compared). The
 * election takes effect at once; no approval step is published for it. Not wired in this slice.
 */
@Suppress("LongParameterList") // the ports of the one operation, as ElectionCommands
class ExemptPurchaseElections(
    private val tx: TransactionRunner,
    private val entities: EntityStore,
    private val store: ExemptPurchaseElectionStore,
    private val authority: ActionAuthority,
    private val caller: CallerSource,
    private val audit: ElectionAudit,
    private val tenants: TenantContext,
    private val clock: Clock,
) {
    fun record(
        entityRef: String,
        taxCodes: List<String>,
        basis: String,
        effectiveFrom: LocalDate,
    ): ExemptPurchaseOutcome =
        tx.inTransaction {
            val entity = entities.entity(entityRef) ?: return@inTransaction ExemptPurchaseOutcome.NotFound
            val actor = caller.actor()
            if (!holds(actor, entity.ref)) {
                return@inTransaction ExemptPurchaseOutcome.Forbidden(
                    "$actor does not hold ${ElectionPermissions.MANAGE} for entity ${entity.ref}",
                )
            }
            ElectionKeys.exemptPurchaseRefusal(taxCodes, basis)?.let {
                return@inTransaction ExemptPurchaseOutcome.Invalid(it)
            }
            store
                .history(entity.ref)
                .firstOrNull { it.effectiveFrom == effectiveFrom && it.taxCodes == taxCodes && it.basis == basis }
                ?.let { return@inTransaction ExemptPurchaseOutcome.Done(it, repeated = true) }
            val row = ExemptPurchaseElectionRow(tenants.current(), entity.ref, effectiveFrom, taxCodes, basis, actor)
            store.insert(row)
            audit.exemptPurchaseRecorded(ExemptPurchaseAuditRecord(entity.ref, taxCodes, actor, clock.instant()))
            ExemptPurchaseOutcome.Done(row)
        }

    /** A check that throws cannot prove the grant: fail closed. */
    private fun holds(
        actor: String,
        entityRef: String,
    ): Boolean =
        try {
            authority.holds(actor, entityRef, ElectionPermissions.MANAGE)
        } catch (_: RuntimeException) {
            false
        }
}
