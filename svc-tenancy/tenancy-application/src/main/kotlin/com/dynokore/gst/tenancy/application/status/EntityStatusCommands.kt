package com.dynokore.gst.tenancy.application.status

import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.gst.tenancy.domain.ExcludedBusinessStatus
import com.dynokore.gst.tenancy.domain.ObligationStartBasis
import com.dynokore.gst.tenancy.domain.StatusKind
import com.dynokore.gst.tenancy.domain.StatusTimeline
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** A request to record a status attribute from an effective date (contract SetEntityStatusRequest). */
data class StatusChange(
    val kind: StatusKind,
    val effectiveFrom: LocalDate,
    val partiallyExempt: Boolean?,
    val excludedBusinessStatus: ExcludedBusinessStatus?,
    val basis: String?,
    val evidenceRef: String?,
    val obligationStartDate: LocalDate?,
    val obligationStartBasis: ObligationStartBasis?,
    val irasReference: String?,
    val obligationStartOverride: Boolean?,
)

sealed interface StatusOutcome {
    /** [repeated] is true when the (entity, kind, effective date) was recorded before and nothing was written. */
    data class Done(
        val entry: EntityStatusEntry,
        val repeated: Boolean = false,
    ) : StatusOutcome

    data object NotFound : StatusOutcome

    /** The request lacks what its status kind requires (INV-REQ-331 b1, INV-REQ-333 b1, INV-REQ-335 b1). */
    data class Invalid(
        val reason: String,
    ) : StatusOutcome
}

/**
 * setEntityStatus (INV-REQ-322, INV-REQ-331): stores the status attribute effective-dated with its basis, keeps the
 * earlier values, and publishes EntityProfileChanged with the node's full state in the same transaction.
 */
class EntityStatusCommands(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val statuses: EntityStatusStore,
    private val publisher: EntityProfileChangePublisher,
) {
    fun setEntityStatus(
        tenant: TenantId,
        entityRef: String,
        change: StatusChange,
    ): StatusOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction StatusOutcome.NotFound
            val entry =
                when (val built = build(change)) {
                    is Built.Refused -> return@inTransaction StatusOutcome.Invalid(built.reason)
                    is Built.Entry -> built.entry
                }
            val history = statuses.history(entity.id)
            history.firstOrNull { it.kind == entry.kind && it.effectiveFrom == entry.effectiveFrom }?.let {
                return@inTransaction StatusOutcome.Done(it, repeated = true)
            }
            val previous = StatusTimeline.inForceOn(history.filter { it.kind == entry.kind }, entry.effectiveFrom)
            statuses.insert(entity.id, entry)
            val version = structure.nextStructureVersion()
            structure.touchEntity(entity.ref, version)
            val updated = entity.copy(structureVersion = version)
            val profile =
                (history + entry)
                    .sortedBy {
                        it.effectiveFrom
                    }.map { ProfileEntry(it.attribute, it.value, it.effectiveFrom) }
            publisher.publish(
                EntityProfileChange(
                    tenant.value,
                    entity.ref,
                    listOf(ChangedAttribute(entry.attribute, previous?.value, entry.value)),
                    entry.effectiveFrom,
                    entityNode(updated, profile),
                ),
            )
            StatusOutcome.Done(entry)
        }

    private sealed interface Built {
        data class Entry(
            val entry: EntityStatusEntry,
        ) : Built

        data class Refused(
            val reason: String,
        ) : Built
    }

    private fun build(change: StatusChange): Built =
        when (change.kind) {
            StatusKind.PARTIAL_EXEMPTION -> partialExemption(change)
            StatusKind.EXCLUDED_BUSINESS -> excludedBusiness(change)
            StatusKind.OBLIGATION_START -> obligationStart(change)
        }

    private fun partialExemption(change: StatusChange): Built {
        val reason =
            when {
                change.partiallyExempt == null -> "partiallyExempt is required"
                change.basis.isNullOrBlank() -> "basis is required with partiallyExempt"
                else -> null
            }
        return reason?.let { Built.Refused(it) }
            ?: Built.Entry(
                EntityStatusEntry(
                    StatusKind.PARTIAL_EXEMPTION,
                    change.effectiveFrom,
                    partiallyExempt = change.partiallyExempt,
                    basis = change.basis,
                ),
            )
    }

    private fun excludedBusiness(change: StatusChange): Built {
        val reason =
            when {
                change.excludedBusinessStatus == null -> "excludedBusinessStatus is required"
                change.basis.isNullOrBlank() -> "basis is required with excludedBusinessStatus"
                change.evidenceRef.isNullOrBlank() -> "evidenceRef is required with excludedBusinessStatus"
                else -> null
            }
        return reason?.let { Built.Refused(it) }
            ?: Built.Entry(
                EntityStatusEntry(
                    StatusKind.EXCLUDED_BUSINESS,
                    change.effectiveFrom,
                    excludedBusinessStatus = change.excludedBusinessStatus,
                    basis = change.basis,
                    evidenceRef = change.evidenceRef,
                ),
            )
    }

    private fun obligationStart(change: StatusChange): Built {
        val basis = change.obligationStartBasis ?: ObligationStartBasis.MANDATORY_DATE
        val reason =
            when {
                change.obligationStartDate == null -> {
                    "obligationStartDate is required"
                }

                basis == ObligationStartBasis.IRAS_COMMUNICATED_DATE && change.irasReference.isNullOrBlank() -> {
                    "irasReference is required when the basis is IRAS_COMMUNICATED_DATE"
                }

                else -> {
                    null
                }
            }
        return reason?.let { Built.Refused(it) }
            ?: Built.Entry(
                EntityStatusEntry(
                    StatusKind.OBLIGATION_START,
                    change.effectiveFrom,
                    obligationStartDate = change.obligationStartDate,
                    obligationStartBasis = basis,
                    irasReference = change.irasReference,
                    obligationStartOverride = change.obligationStartOverride ?: false,
                ),
            )
    }
}
