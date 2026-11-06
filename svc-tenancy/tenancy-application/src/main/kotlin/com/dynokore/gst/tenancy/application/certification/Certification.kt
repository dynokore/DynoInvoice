package com.dynokore.gst.tenancy.application.certification

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.gst.tenancy.domain.CertificationRules
import com.dynokore.gst.tenancy.domain.CertificationStatus
import com.dynokore.gst.tenancy.domain.SandboxCertificationPermissions
import com.dynokore.gst.tenancy.domain.StaleTrigger
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant

/** Whether the entity is registered in the bound tenant (adapter: the entity store). */
fun interface CertifiableEntities {
    fun exists(entityRef: String): Boolean
}

/** One stored runner result of an entity; [failedScenarios] is null exactly when the outcome is PASSED. */
data class CertificationRecord(
    val entityRef: String,
    val outcome: CertificationOutcome,
    val suiteVersion: String,
    val failedScenarios: List<String>?,
    val recordedBy: String,
    val recordedAt: Instant,
)

/** An entity that has a PASSED record, with the tenant it belongs to. */
data class CertifiedEntity(
    val tenant: TenantId,
    val entityRef: String,
)

/** The certification records and stale state of the bound tenant's entities (adapter: the boundary slice). */
interface CertificationStore {
    /** Appends a record; earlier records never change, a repeat of (entityRef, suiteVersion) adds one more. */
    fun append(record: CertificationRecord)

    /** The latest stored record of the entity, or null when it has none. */
    fun latest(entityRef: String): CertificationRecord?

    fun hasPassed(entityRef: String): Boolean

    /** The stale state of an entity that has a PASSED record, else null. */
    fun status(entityRef: String): CertificationStatus?

    /** Sets the state of an entity that has a PASSED record. */
    fun setStatus(
        entityRef: String,
        status: CertificationStatus,
        trigger: StaleTrigger?,
    )
}

/** Every entity with a PASSED record in any tenant, read for the installation-wide train hook. */
fun interface CertifiedEntityDirectory {
    fun certifiedEntities(): List<CertifiedEntity>
}

/** Publishes CertificationStatusChanged (INV-T-16428, INV-Q-16401 A); [trigger] is null for CURRENT. */
fun interface CertificationStatusPublisher {
    fun publish(
        entityRef: String,
        status: CertificationStatus,
        trigger: StaleTrigger?,
    )
}

sealed interface CertifyOutcome {
    data class Recorded(
        val record: CertificationRecord,
    ) : CertifyOutcome

    /** 403 SRT-004: the caller does not hold `sandbox-certification.record`; nothing is stored. */
    data object Forbidden : CertifyOutcome

    /** 404: no such entity. */
    data object NotFound : CertifyOutcome

    /** 400: the request breaks the rule on `failedScenarios`; nothing is stored. */
    data class Invalid(
        val reason: String,
    ) : CertifyOutcome
}

/**
 * certifySandbox (INV-Q-16389, INV-Q-16403): stores the runner result as one more record; a PASSED record of a stale
 * certification makes it CURRENT and publishes one event. The decision is by the permission key through
 * [ActionAuthority] (`AuthorityReplica.decide`), never a role name; an exception counts as not held.
 */
@Suppress("LongParameterList")
class CertifySandbox(
    private val tx: TransactionRunner,
    private val entities: CertifiableEntities,
    private val store: CertificationStore,
    private val authority: ActionAuthority,
    private val publisher: CertificationStatusPublisher,
    private val clock: () -> Instant,
) {
    fun handle(
        actor: String,
        entityRef: String,
        outcome: CertificationOutcome,
        suiteVersion: String,
        failedScenarios: List<String>?,
    ): CertifyOutcome {
        if (!holds(actor, entityRef)) return CertifyOutcome.Forbidden
        return tx.inTransaction {
            val reason = CertificationRules.refusal(outcome, failedScenarios)
            when {
                !entities.exists(entityRef) -> CertifyOutcome.NotFound
                reason != null -> CertifyOutcome.Invalid(reason)
                else -> record(CertificationRecord(entityRef, outcome, suiteVersion, failedScenarios, actor, clock()))
            }
        }
    }

    private fun record(record: CertificationRecord): CertifyOutcome {
        val wasStale = store.status(record.entityRef) == CertificationStatus.STALE
        store.append(record)
        if (record.outcome == CertificationOutcome.PASSED) {
            store.setStatus(record.entityRef, CertificationStatus.CURRENT, null)
            if (wasStale) publisher.publish(record.entityRef, CertificationStatus.CURRENT, null)
        }
        return CertifyOutcome.Recorded(record)
    }

    @Suppress("TooGenericExceptionCaught") // fail closed: any failure of the replica is "not held"
    private fun holds(
        actor: String,
        entityRef: String,
    ): Boolean =
        try {
            authority.holds(actor, entityRef, SandboxCertificationPermissions.RECORD)
        } catch (_: Exception) {
            false
        }
}

sealed interface MarkOutcome {
    /** CURRENT to STALE: one event published. */
    data object Marked : MarkOutcome

    /** Already stale: nothing written, nothing published. */
    data object AlreadyStale : MarkOutcome

    /** The entity has no PASSED record: no mark. */
    data object NoCertification : MarkOutcome

    /** The trigger text is not one of the five kinds. */
    data class UnknownTrigger(
        val text: String,
    ) : MarkOutcome
}

/** The one marking use case with the five trigger kinds, idempotent (INV-Q-16393, INV-REQ-1135). */
class MarkCertificationStale(
    private val tx: TransactionRunner,
    private val store: CertificationStore,
    private val publisher: CertificationStatusPublisher,
) {
    fun mark(
        entityRef: String,
        trigger: String,
    ): MarkOutcome {
        val kind = StaleTrigger.parse(trigger) ?: return MarkOutcome.UnknownTrigger(trigger)
        return mark(entityRef, kind)
    }

    fun mark(
        entityRef: String,
        trigger: StaleTrigger,
    ): MarkOutcome =
        tx.inTransaction {
            when (store.status(entityRef)) {
                null -> {
                    MarkOutcome.NoCertification
                }

                CertificationStatus.STALE -> {
                    MarkOutcome.AlreadyStale
                }

                CertificationStatus.CURRENT -> {
                    store.setStatus(entityRef, CertificationStatus.STALE, trigger)
                    publisher.publish(entityRef, CertificationStatus.STALE, trigger)
                    MarkOutcome.Marked
                }
            }
        }
}

/**
 * The NEW_TRAIN mark of every certified entity (INV-Q-16401 A): each in a transaction bound to the entity's own tenant
 * (ARC-21), nested in the installation-scope call. [mark] must read the store of the tenant bound at the call.
 */
class MarkAllStaleForNewTrain(
    private val directory: CertifiedEntityDirectory,
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
    private val mark: MarkCertificationStale,
    private val actor: String,
) {
    fun run() {
        directory.certifiedEntities().forEach { certified ->
            tenants.bind(certified.tenant, actor) {
                tx.inTransaction { mark.mark(certified.entityRef, StaleTrigger.NEW_TRAIN) }
            }
        }
    }
}

/** Called by RecordInstallationAction when the stored `releaseTrain` fact changes value. */
fun interface ReleaseTrainChanged {
    fun changed()
}
