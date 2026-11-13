package com.dynokore.gst.consoleBff.domain.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
import java.time.Instant
import java.util.UUID

/** Facts of the DocumentHeld payload used by the exception read model; a missing optional member is null. */
data class DocumentHeldFacts(
    val entityRef: String,
    val docUuid: String,
    val docsequence: Long,
    val holdReasons: List<String>,
    val prescribedPeriodRef: String,
    val code: String? = null,
    val remediationOwner: String? = null,
    val disposition: String? = null,
)

/** Facts of the DocumentReleased payload used by the exception read model. */
data class DocumentReleasedFacts(
    val docUuid: String,
    val docsequence: Long,
    val releasedHoldReasons: List<String>,
)

/** One stored exception row. A required contract string member that is missing is the empty string. */
data class ExceptionRow(
    val tenantId: String,
    val exceptionId: UUID,
    val entityRef: String,
    val docUuid: String,
    val code: String,
    val remediationOwner: String,
    val disposition: String,
    val state: ItemState,
    val raisedAt: Instant,
    val periodRef: String,
    val docsequence: Long,
)
