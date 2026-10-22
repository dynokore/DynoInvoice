package com.dynokore.gst.tenancy.application.status

import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.gst.tenancy.domain.Effective
import com.dynokore.gst.tenancy.domain.ExcludedBusinessStatus
import com.dynokore.gst.tenancy.domain.ObligationStartBasis
import com.dynokore.gst.tenancy.domain.StatusKind
import java.time.LocalDate
import java.util.UUID

/** One recorded effective period of a status attribute with its basis (INV-REQ-331); the earlier ones stay readable. */
data class EntityStatusEntry(
    val kind: StatusKind,
    override val effectiveFrom: LocalDate,
    val partiallyExempt: Boolean? = null,
    val excludedBusinessStatus: ExcludedBusinessStatus? = null,
    val basis: String? = null,
    val evidenceRef: String? = null,
    val obligationStartDate: LocalDate? = null,
    val obligationStartBasis: ObligationStartBasis? = null,
    val irasReference: String? = null,
    val obligationStartOverride: Boolean? = null,
) : Effective {
    /** The profile attribute this entry sets and its value as the event carries it (opaque string). */
    val attribute: String
        get() =
            when (kind) {
                StatusKind.PARTIAL_EXEMPTION -> "partiallyExempt"
                StatusKind.EXCLUDED_BUSINESS -> "excludedBusinessStatus"
                StatusKind.OBLIGATION_START -> "obligationStartDate"
            }

    val value: String
        get() =
            when (kind) {
                StatusKind.PARTIAL_EXEMPTION -> checkNotNull(partiallyExempt).toString()
                StatusKind.EXCLUDED_BUSINESS -> checkNotNull(excludedBusinessStatus).name
                StatusKind.OBLIGATION_START -> checkNotNull(obligationStartDate).toString()
            }
}

/** The status history of the entities of the bound tenant; every method runs in the caller's transaction. */
interface EntityStatusStore {
    /** Every entry of the entity, oldest effective date first. */
    fun history(entityId: UUID): List<EntityStatusEntry>

    fun insert(
        entityId: UUID,
        entry: EntityStatusEntry,
    )
}

/** One attribute whose value changed, old and new as the event carries them. */
data class ChangedAttribute(
    val attribute: String,
    val oldValue: String?,
    val newValue: String,
)

/** One change of the entity profile, raised in the command transaction that makes it. */
data class EntityProfileChange(
    val tenant: UUID,
    val entityRef: String,
    val changedAttributes: List<ChangedAttribute>,
    val effectiveDate: LocalDate,
    val node: StructureNodeState,
)

/** Publishes EntityProfileChanged through outbox-and-queue in the caller's transaction (TPL-05, CS-17). */
interface EntityProfileChangePublisher {
    fun publish(change: EntityProfileChange)
}
