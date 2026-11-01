package com.dynokore.gst.access.domain.elevation

import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import java.time.Duration
import java.time.Instant

/** An elevation request opens content of one tenant (`ELV-*`); a diagnostic invitation opens an installation (`DIAG-*`). */
enum class ElevationKind(
    val refPrefix: String,
) {
    ELEVATION("ELV-"),
    DIAGNOSTIC_INVITATION("DIAG-"),
}

enum class ElevationStatus { PENDING, GRANTED, REFUSED, WITHDRAWN }

/**
 * A time-boxed elevation or diagnostic invitation (INV-REQ-862 behaviour 3, INV-REQ-890 behaviour 1). Immutable: a
 * change returns a new instance (CS-07). [subject] is the tenant reference of an elevation, the installation reference
 * of an invitation. [requester] is the operator identity the access opens for. Only a PENDING record can be decided or
 * withdrawn; a record is usable only while GRANTED and inside its [window].
 */
class ElevationRecord(
    val ref: String,
    val kind: ElevationKind,
    val subject: String,
    val requester: IdentityRef,
    val caseId: String,
    val status: ElevationStatus,
    val window: ValidityWindow?,
    val decidedBy: IdentityRef?,
    val version: Long,
) {
    init {
        require(ref.startsWith(kind.refPrefix) && ref.length <= MAX_REF) {
            "a ${kind.name} reference starts with ${kind.refPrefix}"
        }
        require((status == ElevationStatus.GRANTED) == (window != null)) { "exactly a granted record has a time box" }
        require(version >= 0) { "version must not be negative" }
    }

    /** True while the access this record opened is in force at [at]: granted, inside its time box. */
    fun opensAccessAt(at: Instant): Boolean = status == ElevationStatus.GRANTED && window?.isValidAt(at) == true

    /** Grants for [validFor] from [at], by [approver]; null when the record is no longer pending. */
    fun granted(
        approver: IdentityRef,
        at: Instant,
        validFor: Duration,
    ): ElevationRecord? =
        if (status != ElevationStatus.PENDING || validFor.isNegative || validFor.isZero) {
            null
        } else {
            copy(ElevationStatus.GRANTED, ValidityWindow(at, at.plus(validFor)), approver)
        }

    fun refused(approver: IdentityRef): ElevationRecord? =
        if (status == ElevationStatus.PENDING) copy(ElevationStatus.REFUSED, null, approver) else null

    /** Cancels a request that is still pending; null once it has been decided or withdrawn. */
    fun withdrawn(): ElevationRecord? =
        if (status == ElevationStatus.PENDING) copy(ElevationStatus.WITHDRAWN, null, null) else null

    private fun copy(
        to: ElevationStatus,
        box: ValidityWindow?,
        by: IdentityRef?,
    ) = ElevationRecord(ref, kind, subject, requester, caseId, to, box, by, version + 1)

    companion object {
        const val MAX_REF = 64

        fun pending(
            ref: String,
            kind: ElevationKind,
            subject: String,
            requester: IdentityRef,
            caseId: String,
        ) = ElevationRecord(ref, kind, subject, requester, caseId, ElevationStatus.PENDING, null, null, 0)
    }
}

/**
 * The content rule of `authorise` for an operator (INV-REQ-862 behaviours 1 and 3, INV-REQ-888 behaviour 2): a pure
 * function of the records and the instant, so nothing is cached (INV-REQ-1214).
 */
object OperatorAccess {
    const val NO_ELEVATION = "NO_ELEVATION"
    const val NO_INVITATION = "NO_INVITATION"

    /** An approved, unexpired elevation of [requester] for [tenant] exists. */
    fun elevated(
        records: List<ElevationRecord>,
        requester: IdentityRef,
        tenant: String,
        at: Instant,
    ): Boolean =
        records.any {
            it.kind == ElevationKind.ELEVATION &&
                it.requester == requester &&
                it.subject == tenant &&
                it.opensAccessAt(at)
        }

    /** An approved, unexpired diagnostic invitation of [requester] exists for any installation. */
    fun invited(
        records: List<ElevationRecord>,
        requester: IdentityRef,
        at: Instant,
    ): Boolean =
        records.any {
            it.kind == ElevationKind.DIAGNOSTIC_INVITATION && it.requester == requester && it.opensAccessAt(at)
        }
}
