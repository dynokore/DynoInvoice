package com.dynokore.gst.submission.application.drain

import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.queue.DespatchedVersion
import com.dynokore.gst.submission.domain.drain.Tier
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/**
 * The scoped permission keys of the period drain (INV-REQ-554, INV-Q-10027): the person key for a HUMAN and a
 * CONSENT_AGENT principal, the machine key for a MACHINE principal. The preview accepts the person key only.
 */
object DrainActions {
    const val PERSON = "period-readiness.run"
    const val MACHINE = "machine.drain.run"
}

/** The tier number of a drain tier: 1 window open, 2 drained period, 3 routine (INV-REQ-554). */
val Tier.number: Int get() = ordinal + 1

/** A queued document version of the tenant. */
data class QueuedVersion(
    val docUuid: String,
    val version: Int,
    val entityRef: String,
    val periodRef: String,
    val queuedAt: Instant,
)

/** The queued versions of a tenant; null means the backlog cannot be read. */
fun interface DrainBacklog {
    fun queued(tenant: TenantId): List<QueuedVersion>?
}

sealed interface SafetyMarginAnswer {
    data class Known(
        val margin: PackMargin,
    ) : SafetyMarginAnswer

    /** No margin can be named: the drain refuses the whole call (fail closed). */
    data object Unavailable : SafetyMarginAnswer
}

/** The safety margin in force for an entity on a business date (INV-REQ-1279). */
fun interface SafetyMargins {
    fun effective(
        tenant: TenantId,
        entityRef: String,
        on: java.time.LocalDate,
    ): SafetyMarginAnswer
}

enum class ResidualKind { PROVISIONAL, DEAD_LETTERED }

/** A version of the entity and period that the drain could not despatch; [reasonCode] is stored, never invented. */
data class ResidualCandidate(
    val docUuid: String,
    val version: Int,
    val kind: ResidualKind,
    val reasonCode: String?,
    val recordedAt: Instant,
)

/** The residual candidates of an entity and period; null means they cannot be read. */
fun interface ResidualSource {
    fun candidates(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): List<ResidualCandidate>?
}

/** Despatches the next version of the queue in its own transaction; null when nothing is claimable. */
fun interface NextDespatch {
    fun next(): DespatchedVersion?
}

/** The decision of the authority replica (`AuthorityReplica::decide` in the wiring); no role name is read here. */
fun interface DrainAuthority {
    fun decide(
        request: AuthorizationRequest,
        deadline: Deadline,
    ): AuthorityDecision
}

/** Configuration, no default in code: [batchSize] is the number of despatches between two selections. */
data class DrainConfig(
    val batchSize: Int,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }
}
