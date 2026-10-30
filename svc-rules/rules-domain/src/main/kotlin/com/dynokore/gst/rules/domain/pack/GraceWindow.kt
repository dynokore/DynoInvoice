package com.dynokore.gst.rules.domain.pack

import java.time.LocalDate

/** Length of a grace window in days; at least 1 (INV-REQ-3210). */
data class GraceDays(
    val days: Int,
) {
    init {
        require(days >= 1) { "grace days must be at least 1" }
    }
}

enum class DeploymentForm { SAAS, PRIVATE }

enum class ReviewPolicy { REVIEW_IN_GRACE_WINDOW, NOTICE_ONLY }

sealed interface GraceEnd {
    data class Ends(
        val on: LocalDate,
    ) : GraceEnd
}

sealed interface GraceDecision {
    data class Open(
        val endsOn: LocalDate,
    ) : GraceDecision

    /** The pack is activated after review. */
    data object ActivateReviewed : GraceDecision

    /** The pack is activated without review; the record flag of INV-REQ-952 behavior 2. */
    data object ActivateUnreviewed : GraceDecision
}

/** The private form has no grace window (INV-REQ-1318 behavior 2). */
data object NoGraceWindow : GraceEnd, GraceDecision

/** Pure grace-window decision (INV-REQ-858, 952, 1300, 1318); the caller supplies every number. */
object GraceWindow {
    fun effective(
        stored: GraceDays?,
        maximum: GraceDays,
    ): GraceDays = if (stored == null || stored.days > maximum.days) maximum else stored

    fun endsOn(
        form: DeploymentForm,
        deliveredOn: LocalDate,
        effectiveFrom: LocalDate,
        stored: GraceDays?,
        maximum: GraceDays,
    ): GraceEnd {
        if (form == DeploymentForm.PRIVATE) return NoGraceWindow
        val byDays = deliveredOn.plusDays(effective(stored, maximum).days.toLong())
        return GraceEnd.Ends(if (byDays.isBefore(effectiveFrom)) byDays else effectiveFrom)
    }

    // The signature is fixed by INV-T-13143: every set-point and date is an argument (12-factor III), no clock.
    @Suppress("LongParameterList")
    fun decide(
        form: DeploymentForm,
        policy: ReviewPolicy,
        deliveredOn: LocalDate,
        effectiveFrom: LocalDate,
        stored: GraceDays?,
        maximum: GraceDays,
        reviewed: Boolean,
        today: LocalDate,
    ): GraceDecision =
        when {
            form == DeploymentForm.PRIVATE -> {
                NoGraceWindow
            }

            policy == ReviewPolicy.NOTICE_ONLY -> {
                openUntil(effectiveFrom, today)
            }

            reviewed -> {
                GraceDecision.ActivateReviewed
            }

            else -> {
                when (val end = endsOn(form, deliveredOn, effectiveFrom, stored, maximum)) {
                    is GraceEnd.Ends -> openUntil(end.on, today)
                    NoGraceWindow -> NoGraceWindow
                }
            }
        }

    private fun openUntil(
        end: LocalDate,
        today: LocalDate,
    ): GraceDecision = if (today.isBefore(end)) GraceDecision.Open(end) else GraceDecision.ActivateUnreviewed
}
