package com.dynokore.gst.access.domain.elevation

import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Permission
import java.time.Duration
import java.time.Instant

/** An elevation request opens content of one tenant (`ELV-*`); an invitation opens an installation (`DIAG-*`). */
enum class ElevationKind(
    val refPrefix: String,
) {
    ELEVATION("ELV-"),
    DIAGNOSTIC_INVITATION("DIAG-"),
}

enum class ElevationStatus { PENDING, GRANTED, REFUSED, WITHDRAWN }

/** What a record asks for: [subject] is the tenant of an elevation, the installation of an invitation. */
data class ElevationAsk(
    val kind: ElevationKind,
    val subject: String,
    val requester: IdentityRef,
    val caseId: String,
)

/**
 * A time-boxed elevation or diagnostic invitation (INV-REQ-862 behaviour 3, INV-REQ-890 behaviour 1). Immutable: a
 * change returns a new instance (CS-07). The requester of [ask] is the operator identity the access opens for. Only a
 * PENDING record can be decided or withdrawn; a record is usable only while GRANTED and inside its [window].
 */
@Suppress("LongParameterList") // one field per column of the stored record
class ElevationRecord(
    val ref: String,
    val tenant: TenantRef,
    val ask: ElevationAsk,
    val status: ElevationStatus,
    val window: ValidityWindow?,
    val decidedBy: IdentityRef?,
    val version: Long,
) {
    val kind: ElevationKind get() = ask.kind
    val subject: String get() = ask.subject
    val requester: IdentityRef get() = ask.requester
    val caseId: String get() = ask.caseId

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
    ) = ElevationRecord(ref, tenant, ask, to, box, by, version + 1)

    companion object {
        const val MAX_REF = 64

        fun pending(
            ref: String,
            tenant: TenantRef,
            ask: ElevationAsk,
        ) = ElevationRecord(ref, tenant, ask, ElevationStatus.PENDING, null, null, 0)
    }
}

/**
 * The content rule of `authorise` for an operator (INV-REQ-862 behaviours 1 and 3, INV-REQ-888 behaviour 2): a pure
 * function of the records and the instant, so nothing is cached (INV-REQ-1214).
 */
object OperatorAccess {
    const val NO_ELEVATION = "NO_ELEVATION"
    const val NO_INVITATION = "NO_INVITATION"

    /** The one set of permission keys an approved elevation opens (INV-Q-15954 A): invoice content, nothing else. */
    val OPENED_BY_ELEVATION: Set<Permission> = setOf(Permission("doc.view.content"))

    /** An approved, unexpired elevation of [requester] for [tenant] exists. */
    fun elevated(
        records: List<ElevationRecord>,
        requester: IdentityRef,
        tenant: TenantRef,
        at: Instant,
    ): Boolean =
        records.any {
            it.kind == ElevationKind.ELEVATION &&
                it.requester == requester &&
                it.tenant == tenant &&
                it.subject == tenant.value &&
                it.opensAccessAt(at)
        }

    /** An approved, unexpired diagnostic invitation of [requester] for the data of [tenant] exists (INV-Q-15952 A). */
    fun invited(
        records: List<ElevationRecord>,
        requester: IdentityRef,
        tenant: TenantRef,
        at: Instant,
    ): Boolean = invitedHere(records.filter { it.tenant == tenant }, requester, at)

    /** As [invited], for [records] that one read bound to one tenant returned (row-level security keeps the rest). */
    fun invitedHere(
        records: List<ElevationRecord>,
        requester: IdentityRef,
        at: Instant,
    ): Boolean =
        records.any {
            it.kind == ElevationKind.DIAGNOSTIC_INVITATION && it.requester == requester && it.opensAccessAt(at)
        }
}
