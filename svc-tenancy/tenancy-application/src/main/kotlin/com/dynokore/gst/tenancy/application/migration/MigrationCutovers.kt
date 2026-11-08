package com.dynokore.gst.tenancy.application.migration

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.status.ChangedAttribute
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The profile attribute the cutover date is published under on EntityProfileChanged (INV-Q-6232 A). */
const val MIGRATION_CUTOVER_ATTRIBUTE = "migrationCutoverDate"

private const val PRIOR_SOLUTION_MAX = 128
private const val EVIDENCE_REF_MAX = 64

/** The one stored cutover of an entity (INV-REQ-1109); [approvedBy] is the approving user id, never a role. */
data class MigrationCutover(
    val entityRef: String,
    val cutoverDate: LocalDate,
    val priorSolution: String,
    val approvedBy: String,
    val evidenceRef: String?,
    val recordedAt: Instant,
)

/** The request of recordMigrationCutover; [approvedBy] is optional and, when given, must equal the acting user ID. */
data class NewMigrationCutover(
    val cutoverDate: LocalDate,
    val priorSolution: String,
    val approvedBy: String? = null,
    val evidenceRef: String? = null,
)

/** The permission keys of the two operations, compared by equality (INV-Q-10027); never a role name. */
object MigrationCutoverPermissions {
    const val RECORD = "migration-cutover.record"
    const val VIEW = "migration-cutover.view"
}

/** The cutovers of the bound tenant; every method runs in the caller's transaction. */
interface MigrationCutoverStore {
    fun find(entityRef: String): MigrationCutover?

    /** Inserts the row; false (nothing written) when the entity already has one, also under a concurrent insert. */
    fun insert(
        entityId: UUID,
        cutover: MigrationCutover,
    ): Boolean
}

/** The cutover as a profile entry: `migrationCutoverDate`, the date as yyyy-MM-dd, effective from that day. */
fun migrationCutoverEntries(cutover: MigrationCutover?): List<ProfileEntry> =
    listOfNotNull(
        cutover?.let { ProfileEntry(MIGRATION_CUTOVER_ATTRIBUTE, it.cutoverDate.toString(), it.cutoverDate) },
    )

/** The cutover entry of an entity's profile, for the structure read and for every EntityProfileChanged node. */
class MigrationCutoverProfileExtras(
    private val store: MigrationCutoverStore,
) : ProfileExtras {
    override fun entries(entityRef: String): List<ProfileEntry> = migrationCutoverEntries(store.find(entityRef))
}

sealed interface MigrationCutoverOutcome {
    /** 201: the first record was stored and one profile command requested. */
    data class Created(
        val cutover: MigrationCutover,
    ) : MigrationCutoverOutcome

    /** 200: the stored record, for an exact repeat of the date (nothing written or requested) and for the read. */
    data class Existing(
        val cutover: MigrationCutover,
    ) : MigrationCutoverOutcome

    /** 403: the caller does not hold the permission key, or the check failed (fail closed). */
    data object Denied : MigrationCutoverOutcome

    /** 404: no such entity in the caller's tenant, or (for the read) the entity has no cutover. */
    data object NotFound : MigrationCutoverOutcome

    /** 409: another cutover date is already recorded; nothing changes. */
    data class Conflict(
        val existing: MigrationCutover,
    ) : MigrationCutoverOutcome

    /** 400: a property breaks its rule or the acting user is empty; nothing is stored. */
    data class Rejected(
        val reason: String,
    ) : MigrationCutoverOutcome
}

private fun ActionAuthority.holdsOrFalse(
    person: String,
    entityRef: String,
    action: String,
): Boolean =
    try {
        holds(person, entityRef, action)
    } catch (_: RuntimeException) {
        false
    }

/** getMigrationCutover (INV-REQ-1109): the cutover of an entity of the caller's tenant, for the view key. */
class GetMigrationCutover(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val store: MigrationCutoverStore,
    private val authority: ActionAuthority,
) {
    fun get(
        actor: String,
        entityRef: String,
    ): MigrationCutoverOutcome {
        if (actor.isEmpty() || !authority.holdsOrFalse(actor, entityRef, MigrationCutoverPermissions.VIEW)) {
            return MigrationCutoverOutcome.Denied
        }
        return tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction MigrationCutoverOutcome.NotFound
            store.find(entity.ref)?.let { MigrationCutoverOutcome.Existing(it) } ?: MigrationCutoverOutcome.NotFound
        }
    }
}

/**
 * recordMigrationCutover (INV-REQ-1109, INV-Q-6232 A): one cutover per entity. The permission is decided per request
 * and `approvedBy` is the acting user ID. The first record stores the row and publishes one EntityProfileChanged in
 * the same transaction; an exact repeat answers the stored record and requests nothing; another date is refused.
 */
@Suppress("LongParameterList") // the ports of the one operation, as the other use cases
class RecordMigrationCutover(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val store: MigrationCutoverStore,
    private val statuses: EntityStatusStore,
    private val publisher: EntityProfileChangePublisher,
    private val extras: ProfileExtras,
    private val authority: ActionAuthority,
    private val tenants: TenantContext,
    private val clock: Clock,
) {
    fun record(
        actor: String,
        entityRef: String,
        request: NewMigrationCutover,
    ): MigrationCutoverOutcome {
        val invalid = if (actor.isEmpty()) "the acting user is empty" else null
        return when {
            invalid != null -> {
                MigrationCutoverOutcome.Rejected(invalid)
            }

            !authority.holdsOrFalse(actor, entityRef, MigrationCutoverPermissions.RECORD) -> {
                MigrationCutoverOutcome.Denied
            }

            else -> {
                invalidReason(actor, request)?.let { MigrationCutoverOutcome.Rejected(it) }
                    ?: storeFirst(actor, entityRef, request)
            }
        }
    }

    private fun storeFirst(
        actor: String,
        entityRef: String,
        request: NewMigrationCutover,
    ): MigrationCutoverOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction MigrationCutoverOutcome.NotFound
            val stored = store.find(entity.ref)
            if (stored != null) return@inTransaction answerExisting(stored, request)
            val row =
                MigrationCutover(
                    entity.ref,
                    request.cutoverDate,
                    request.priorSolution,
                    actor,
                    request.evidenceRef,
                    // microseconds: what timestamptz keeps, so the first answer and a later read carry the same instant
                    clock.instant().truncatedTo(ChronoUnit.MICROS),
                )
            if (store.insert(entity.id, row)) {
                publish(entity.ref, row)
                MigrationCutoverOutcome.Created(row)
            } else {
                answerExisting(checkNotNull(store.find(entity.ref)) { "cutover of ${entity.ref} vanished" }, request)
            }
        }

    private fun answerExisting(
        stored: MigrationCutover,
        request: NewMigrationCutover,
    ): MigrationCutoverOutcome =
        if (stored.cutoverDate == request.cutoverDate) {
            MigrationCutoverOutcome.Existing(stored)
        } else {
            MigrationCutoverOutcome.Conflict(stored)
        }

    /** One publish carries every profile entry, as a StructureNode replaces the node's whole state. */
    private fun publish(
        entityRef: String,
        row: MigrationCutover,
    ) {
        val entity = checkNotNull(structure.entity(entityRef))
        val version = structure.nextStructureVersion()
        structure.touchEntity(entity.ref, version)
        val profile =
            (
                statuses.history(entity.id).map { ProfileEntry(it.attribute, it.value, it.effectiveFrom) } +
                    extras.entries(entity.ref)
            ).sortedBy { it.effectiveDate }
        publisher.publish(
            EntityProfileChange(
                tenants.current().value,
                entity.ref,
                listOf(ChangedAttribute(MIGRATION_CUTOVER_ATTRIBUTE, null, row.cutoverDate.toString())),
                row.cutoverDate,
                entityNode(entity.copy(structureVersion = version), profile),
            ),
        )
    }

    private fun invalidReason(
        actor: String,
        request: NewMigrationCutover,
    ): String? =
        when {
            request.approvedBy != null && request.approvedBy != actor -> {
                "approvedBy must equal the acting user"
            }

            request.priorSolution.isEmpty() || request.priorSolution.length > PRIOR_SOLUTION_MAX -> {
                "priorSolution has 1 to $PRIOR_SOLUTION_MAX characters"
            }

            (request.evidenceRef?.length ?: 0) > EVIDENCE_REF_MAX -> {
                "evidenceRef has at most $EVIDENCE_REF_MAX characters"
            }

            else -> {
                null
            }
        }
}
