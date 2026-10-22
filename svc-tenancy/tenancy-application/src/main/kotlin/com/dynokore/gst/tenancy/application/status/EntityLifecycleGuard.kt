package com.dynokore.gst.tenancy.application.status

import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.domain.ExcludedBusinessStatus
import com.dynokore.gst.tenancy.domain.StatusKind
import com.dynokore.gst.tenancy.domain.StatusTimeline
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** The status gate of go-live (INV-REQ-333). */
sealed interface GoLiveStatusDecision {
    /** The entity's Excluded Business status is NOT_EXCLUDED, or it is an excluded member whose group transmits. */
    data class Allowed(
        val status: ExcludedBusinessStatus,
    ) : GoLiveStatusDecision

    /** go-live is refused: the entity is excluded and its group omits the member's data (INV-AC-353, INV-AC-2313). */
    data class Refused(
        val status: ExcludedBusinessStatus,
    ) : GoLiveStatusDecision

    /** No Excluded Business determination is recorded for the entity on the date. */
    data object NotDetermined : GoLiveStatusDecision

    data object EntityNotFound : GoLiveStatusDecision
}

/**
 * The entity-lifecycle guard exposed for goLive (INV-T-4931 calls it): an entity whose excludedBusinessStatus on the
 * date is anything other than NOT_EXCLUDED does not go live, except an excluded member of a GST group whose group
 * elects to transmit its data (INV-REQ-322, INV-REQ-333, INV-REQ-716, INV-REQ-1547).
 */
class EntityLifecycleGuard(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val statuses: EntityStatusStore,
    private val elections: GroupElectionReader,
) {
    /**
     * The status gate of go-live on [on]. An entity with no Excluded Business determination is refused (fail closed,
     * INV-Q-8735 A): [GoLiveStatusDecision.NotDetermined] is a refusal for the caller. The election of the entity's
     * parent group (VP-64) is read on [on] only for an excluded entity.
     */
    fun checkStatus(
        entityRef: String,
        on: LocalDate,
    ): GoLiveStatusDecision =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction GoLiveStatusDecision.EntityNotFound
            val history = statuses.history(entity.id).filter { it.kind == StatusKind.EXCLUDED_BUSINESS }
            val status =
                StatusTimeline.inForceOn(history, on)?.excludedBusinessStatus
                    ?: return@inTransaction GoLiveStatusDecision.NotDetermined
            if (!status.excluded || elections.transmitsExcludedMember(entity.groupRef, on)) {
                GoLiveStatusDecision.Allowed(status)
            } else {
                GoLiveStatusDecision.Refused(status)
            }
        }
}

/** Reads of the status attributes by transaction date (INV-REQ-331 b3). */
class EntityStatusQueries(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val statuses: EntityStatusStore,
) {
    /** The entry of [kind] in force on [date], or null when none was, or the entity is not in the tenant. */
    fun inForceOn(
        entityRef: String,
        kind: StatusKind,
        date: LocalDate,
    ): EntityStatusEntry? =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction null
            StatusTimeline.inForceOn(statuses.history(entity.id).filter { it.kind == kind }, date)
        }

    /** Every recorded entry of [kind], oldest first: the earlier values stay readable. */
    fun history(
        entityRef: String,
        kind: StatusKind,
    ): List<EntityStatusEntry> =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction emptyList()
            statuses.history(entity.id).filter { it.kind == kind }
        }
}
