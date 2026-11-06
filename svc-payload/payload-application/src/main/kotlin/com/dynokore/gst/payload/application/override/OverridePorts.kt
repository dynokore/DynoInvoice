package com.dynokore.gst.payload.application.override

import com.dynokore.gst.payload.domain.override.OverrideStatus
import com.dynokore.gst.payload.domain.override.ReleaseOutcome
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** The permission key of the request, the approval and the read (catalogue key `validation.override`). */
const val VALIDATION_OVERRIDE = "validation.override"

/** Decides one scoped permission per request (`AuthorityReplica.decide` in the composition root, INV-Q-10027). */
fun interface OverrideAuthority {
    fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean
}

/** What the stored version of a document says about its entity and its total (INV-REQ-1293). */
data class DocumentVersionFacts(
    val entityRef: String,
    /** The total in SGD including GST, read from the stored version and never from the request. */
    val totalSgd: BigDecimal,
)

/** The stored document versions; the tenant is the one bound to the request. */
fun interface DocumentVersions {
    /** The facts of the version, or nothing for an unknown version or one of another tenant. */
    fun find(
        docUuid: UUID,
        version: Int,
    ): DocumentVersionFacts?
}

/** The question put to `decideOverrideRelease`; the total is the stored one, never the request's. */
data class ReleaseRequest(
    val entityRef: String,
    val docUuid: UUID,
    val version: Int,
    val errorCode: String,
    val totalSgd: BigDecimal,
    val approverRef: String,
    val priorApproverRefs: List<String>,
)

/** Asks `decideOverrideRelease`. An error, a timeout or an unreadable answer is [ReleaseOutcome.Denied]. */
fun interface ReleaseDecisions {
    fun decide(request: ReleaseRequest): ReleaseOutcome
}

/** Whether the stored findings of a version let a code be overridden. */
enum class FindingDisposition {
    /** The version carries the code, every stored finding of it is overridable and the catalogue says OVERRIDABLE. */
    OVERRIDABLE,

    /** MANUAL, TERMINAL, unknown to the catalogue, not carried by the version, or unreadable: fail closed. */
    REFUSED,
}

/** The stored blocking findings of a document version and the catalogue disposition of a code. */
fun interface BlockingFindings {
    fun disposition(
        docUuid: UUID,
        version: Int,
        code: String,
    ): FindingDisposition
}

/** An override before it is stored; [status] PENDING or RELEASED. */
data class NewOverride(
    val docUuid: UUID,
    val version: Int,
    val code: String,
    val reason: String,
    val status: OverrideStatus,
    val requestedBy: String,
    val releasedBy: String?,
) {
    init {
        require(
            (status == OverrideStatus.RELEASED) == (releasedBy != null),
        ) { "releasedBy is set exactly when RELEASED" }
    }
}

/** A stored override. */
data class StoredOverride(
    val docUuid: UUID,
    val version: Int,
    val code: String,
    val reason: String,
    val status: OverrideStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val releasedBy: String?,
    val releasedAt: Instant?,
)

/** The store of the overrides of the caller's tenant; a row of another tenant is never seen (forced RLS). */
interface OverrideStore {
    /** Stores the row; nothing when the (doc, version, code) key is already taken. */
    fun insert(override: NewOverride): StoredOverride?

    fun find(
        docUuid: UUID,
        version: Int,
        code: String,
    ): StoredOverride?

    /** One conditional update from PENDING to RELEASED; the released row, or nothing when it changed no row. */
    fun release(
        docUuid: UUID,
        version: Int,
        code: String,
        releasedBy: String,
    ): StoredOverride?

    /** The entries of one version ordered by `requestedAt`, then `code`, after the cursor position, at most [limit]. */
    fun entries(
        docUuid: UUID,
        version: Int,
        after: Pair<Instant, String>?,
        limit: Int,
    ): List<StoredOverride>
}

/** The audit trail; entries name the acting user ID, never a role (INV-Q-10647). */
interface AuditTrail {
    /** A request, naming the first approver and the reason. */
    fun requested(
        userId: String,
        override: StoredOverride,
    )

    /** A release, naming the releasing approver and the first approver. */
    fun released(
        userId: String,
        override: StoredOverride,
    )
}
