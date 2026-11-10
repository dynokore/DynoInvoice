package com.dynokore.gst.controlPlane.domain.schedule

import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import java.time.Instant

/** The states of the contract's `TenantScheduleResponse.state`; EXECUTED, FAILED and the closing are INV-T-8180. */
enum class ScheduleState { AWAITING_APPROVAL, APPROVED, EXECUTED, FAILED, CANCELLED }

/** What a pending request asks for. */
enum class PendingKind { CHANGE, CANCELLATION }

/** The named retention custody holder the closing needs (INV-REQ-956); stored with the schedule. */
data class CustodyHolder(
    val name: String,
    val contactEmail: String,
    val contactMobile: String?,
)

/** A change or a cancellation that waits for approval; the approved schedule stays in force until then. */
data class PendingRequest(
    val kind: PendingKind,
    val goLiveAt: Instant?,
    val offboardingAt: Instant?,
    val requestedBy: String,
) {
    init {
        require(kind == PendingKind.CHANGE || (goLiveAt == null && offboardingAt == null)) {
            "a cancellation carries no time"
        }
        require(kind == PendingKind.CANCELLATION || goLiveAt != null || offboardingAt != null) {
            "a change names a time"
        }
    }
}

/** Why a schedule or a request is refused with 422 (INV-REQ-8100 behavior 1). */
object ScheduleRule {
    const val MISSING_CONTRACT = "SCHEDULE_CONTRACT_MISSING"
    const val OFFBOARDING_NOT_AFTER_GO_LIVE = "SCHEDULE_OFFBOARDING_NOT_AFTER_GO_LIVE"
    const val GO_LIVE_NOT_AFTER_NOW = "SCHEDULE_GO_LIVE_NOT_AFTER_NOW"

    /** The refusal of a pair of times against the moment of setting, or null when they hold. */
    fun timesRefusal(
        goLiveAt: Instant,
        offboardingAt: Instant,
        now: Instant,
        goLiveChanged: Boolean = true,
    ): String? =
        when {
            !offboardingAt.isAfter(goLiveAt) -> OFFBOARDING_NOT_AFTER_GO_LIVE
            goLiveChanged && !goLiveAt.isAfter(now) -> GO_LIVE_NOT_AFTER_NOW
            else -> null
        }
}

/**
 * The go-live and offboarding schedule of a tenant (INV-REQ-8100). It is set AWAITING_APPROVAL by [setBy] and takes
 * effect only when a different holder approves it. [openedAt] is the moment the go-live was carried out (the
 * `openTenant` call succeeded); a time that was carried out cannot be changed.
 */
data class TenantSchedule(
    val tenantRef: CommercialRef,
    val scheduleRef: String,
    val contractReference: CommercialRef,
    val state: ScheduleState,
    val goLiveAt: Instant,
    val offboardingAt: Instant,
    val setBy: String,
    val approvedBy: String?,
    val custodyHolder: CustodyHolder,
    val noticeWindowDays: Int?,
    val openedAt: Instant?,
    /** A requested change of the times, awaiting approval. */
    val pendingChange: PendingRequest? = null,
    /** A requested cancellation, awaiting approval. */
    val pendingCancellation: PendingRequest? = null,
    /** The run time of the latest opening attempt that did not open the tenant, or null when none was made. */
    val lastOpenAttemptAt: Instant? = null,
) {
    init {
        require(pendingChange == null || pendingChange.kind == PendingKind.CHANGE) { "pendingChange is a change" }
        require(pendingCancellation == null || pendingCancellation.kind == PendingKind.CANCELLATION) {
            "pendingCancellation is a cancellation"
        }
    }

    /** The pending request of [kind], or null. */
    fun pendingOf(kind: PendingKind): PendingRequest? =
        when (kind) {
            PendingKind.CHANGE -> pendingChange
            PendingKind.CANCELLATION -> pendingCancellation
        }

    /** The go-live was carried out. */
    val goLiveCarriedOut: Boolean get() = openedAt != null

    /** The schedule with the pending request of [kind] approved by [approver]; a cancellation drops a change. */
    fun approvePending(
        kind: PendingKind,
        approver: String,
    ): TenantSchedule {
        val request = checkNotNull(pendingOf(kind)) { "no $kind is pending" }
        return when (kind) {
            PendingKind.CANCELLATION -> {
                copy(
                    state = ScheduleState.CANCELLED,
                    approvedBy = approver,
                    pendingChange = null,
                    pendingCancellation = null,
                )
            }

            PendingKind.CHANGE -> {
                copy(
                    goLiveAt = request.goLiveAt ?: goLiveAt,
                    offboardingAt = request.offboardingAt ?: offboardingAt,
                    approvedBy = approver,
                    pendingChange = null,
                )
            }
        }
    }

    companion object {
        const val MAX_REF = 64
    }
}
