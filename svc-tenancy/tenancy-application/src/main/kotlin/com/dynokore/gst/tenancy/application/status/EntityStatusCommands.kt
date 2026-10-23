package com.dynokore.gst.tenancy.application.status

import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.GroupKind
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.gst.tenancy.domain.ExcludedBusinessStatus
import com.dynokore.gst.tenancy.domain.ImplementationDate
import com.dynokore.gst.tenancy.domain.ObligationStartBasis
import com.dynokore.gst.tenancy.domain.ObligationStartRule
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
    val registrationStartDate: LocalDate? = null,
)

sealed interface StatusOutcome {
    /** [repeated] is true when the (entity, kind, effective date) was recorded before and nothing was written. */
    data class Done(
        val entry: EntityStatusEntry,
        val repeated: Boolean = false,
    ) : StatusOutcome

    data object NotFound : StatusOutcome

    /** The same (entity, kind, effective date) was recorded with other values; nothing is written (INV-Q-8735 A). */
    data class Conflict(
        val existing: EntityStatusEntry,
    ) : StatusOutcome

    /**
     * No obligation start can be derived yet, so no record is written and the date stays outstanding (INV-REQ-1312):
     * [reason] says which input is missing.
     */
    data class Outstanding(
        val reason: String,
    ) : StatusOutcome

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
    private val assessments: PhaseAssessmentStore,
    private val implementation: ImplementationBasisReader,
) {
    fun setEntityStatus(
        tenant: TenantId,
        entityRef: String,
        change: StatusChange,
    ): StatusOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction StatusOutcome.NotFound
            val entry =
                when (val built = build(change, entity)) {
                    is Built.Refused -> return@inTransaction StatusOutcome.Invalid(built.reason)
                    is Built.Outstanding -> return@inTransaction StatusOutcome.Outstanding(built.reason)
                    is Built.Entry -> built.entry
                }
            val history = statuses.history(entity.id)
            history.firstOrNull { it.kind == entry.kind && it.effectiveFrom == entry.effectiveFrom }?.let {
                return@inTransaction if (it.sameValues(entry)) {
                    StatusOutcome.Done(it, repeated = true)
                } else {
                    StatusOutcome.Conflict(it)
                }
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

        data class Outstanding(
            val reason: String,
        ) : Built
    }

    private fun build(
        change: StatusChange,
        entity: EntityRecord,
    ): Built =
        when (change.kind) {
            StatusKind.PARTIAL_EXEMPTION -> partialExemption(change)
            StatusKind.EXCLUDED_BUSINESS -> excludedBusiness(change)
            StatusKind.OBLIGATION_START -> obligationStart(change, entity)
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

    /**
     * INV-REQ-335, INV-Q-8733 A: the service derives the obligation start, the later of the registration start date and
     * the implementation date of VP-51 resolved on the effective date. What the caller supplies of the derived fields
     * must equal the derived value.
     */
    private fun obligationStart(
        change: StatusChange,
        entity: EntityRecord,
    ): Built {
        val implementation = implementationDate(change, entity)
        return when (implementation) {
            is Implementation.Missing -> {
                Built.Outstanding(implementation.reason)
            }

            is Implementation.Found -> {
                val derived = ObligationStartRule.derive(change.registrationStartDate, implementation.date)
                val entry =
                    EntityStatusEntry(
                        StatusKind.OBLIGATION_START,
                        change.effectiveFrom,
                        obligationStartDate = derived.date,
                        obligationStartBasis = derived.implementation.basis,
                        irasReference = derived.implementation.irasReference,
                        obligationStartOverride = derived.implementation.override,
                        registrationStartDate = derived.registrationStartDate,
                    )
                suppliedDifference(change, entry)?.let { Built.Refused(it) } ?: Built.Entry(entry)
            }
        }
    }

    private sealed interface Implementation {
        data class Found(
            val date: ImplementationDate,
        ) : Implementation

        data class Missing(
            val reason: String,
        ) : Implementation
    }

    private fun implementationDate(
        change: StatusChange,
        entity: EntityRecord,
    ): Implementation =
        when (val basis = implementation.resolve(entity.groupRef, entity.ref, change.effectiveFrom)) {
            is ResolvedImplementation.MandatoryDate -> {
                if (!basis.recorded && structure.group(entity.groupRef)?.kind == GroupKind.GST_GROUP) {
                    return Implementation.Missing(
                        "the GST group has no communicated implementation date (VP-51) for the entity or its group",
                    )
                }
                assessments.latest(entity.id)?.let {
                    val mandatory = it.assessment.mandatoryDate
                    Implementation.Found(ImplementationDate(mandatory, ObligationStartBasis.MANDATORY_DATE))
                } ?: Implementation.Missing("no mandatory date is stored: run assessPhase for the entity first")
            }

            is ResolvedImplementation.EarlierVoluntaryDate -> {
                Implementation.Found(ImplementationDate(basis.date, ObligationStartBasis.EARLIER_VOLUNTARY_DATE))
            }

            is ResolvedImplementation.IrasCommunicatedDate -> {
                Implementation.Found(
                    ImplementationDate(basis.date, ObligationStartBasis.IRAS_COMMUNICATED_DATE, basis.irasReference),
                )
            }
        }

    /**
     * The first field the caller supplied (its key is present, so it is not null) that differs from the derived [entry],
     * or null (one source, INV-Q-8733 A, INV-Q-8903 A): the contract has no defaults, so presence is the null test.
     */
    private fun suppliedDifference(
        change: StatusChange,
        entry: EntityStatusEntry,
    ): String? =
        when {
            change.obligationStartDate != null && change.obligationStartDate != entry.obligationStartDate -> {
                "obligationStartDate differs from the derived ${entry.obligationStartDate}"
            }

            change.obligationStartBasis != null && change.obligationStartBasis != entry.obligationStartBasis -> {
                "obligationStartBasis differs from the derived ${entry.obligationStartBasis}"
            }

            change.irasReference != null && change.irasReference != entry.irasReference -> {
                "irasReference differs from the derived value"
            }

            change.obligationStartOverride != null &&
                change.obligationStartOverride != entry.obligationStartOverride -> {
                "obligationStartOverride differs from the derived ${entry.obligationStartOverride}"
            }

            else -> {
                null
            }
        }
}
