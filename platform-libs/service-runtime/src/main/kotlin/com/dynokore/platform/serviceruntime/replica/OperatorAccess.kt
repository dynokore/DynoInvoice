package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/**
 * The state of an operator access record (coordinator ruling INV-Q-16496 A). The wire string is read by exact
 * equality: `ACTIVE` is [ACTIVE] and every other string (including `active`, an empty or an unknown one) is [ENDED],
 * so a value the replica does not know never opens access (fail closed).
 */
enum class OperatorAccessState {
    ACTIVE,
    ENDED,
    ;

    companion object {
        fun of(wire: String?): OperatorAccessState = if (wire == ACTIVE.name) ACTIVE else ENDED
    }
}

/**
 * The approved elevation of an operator for a tenant (INV-REQ-862 b3, owner answer INV-Q-4119 A): `doc.view.content`
 * is open from [validFrom] up to, not including, [validUntil].
 */
data class OperatorElevation(
    val elevationRef: String,
    val validFrom: Instant,
    val validUntil: Instant,
) {
    fun isOpenAt(at: Instant): Boolean = !at.isBefore(validFrom) && at.isBefore(validUntil)
}

/**
 * What access-svc resolved for one operator session (coordinator ruling INV-Q-16496 A, carrier A1): the `act.case`
 * claim [caseRef] (at most 64), the `act.role` claim [role] (at most 100), the [state], the metadata keys
 * [permissions] (at most 8; the replica never resolves a role itself), the end of the session [validUntil] (never
 * later than the token `exp`) and the optional approved [elevation]. `doc.view.content` found in [permissions] is
 * ignored: the content key is opened by the [elevation] only (INV-REQ-862 b3).
 */
data class OperatorAccess(
    val caseRef: String,
    val role: String,
    val state: OperatorAccessState,
    val permissions: Set<String>,
    val validUntil: Instant,
    val elevation: OperatorElevation? = null,
) {
    fun isExpiredAt(at: Instant): Boolean = !at.isBefore(validUntil)

    companion object {
        /** The one permission key that only an approved, unexpired elevation of the operator can open. */
        const val CONTENT_KEY = "doc.view.content"
    }
}

/** One operator access record's key: the operator and the case in one tenant. */
data class OperatorAccessKey(
    val tenant: TenantId,
    val principalId: String,
    val caseRef: String,
)
