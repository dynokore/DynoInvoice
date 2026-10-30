package com.dynokore.gst.evidence.domain.cases

import java.time.Duration
import java.time.Instant

/** The terms of a breach case: how long after each start each step is due. No default exists. */
data class BreachTerms(
    val assessmentWithin: Duration,
    val notificationWithin: Duration,
) {
    init {
        require(assessmentWithin > Duration.ZERO) { "assessmentWithin must be greater than zero" }
        require(notificationWithin > Duration.ZERO) { "notificationWithin must be greater than zero" }
    }
}

/** The two deadlines tracked on a breach case. */
enum class BreachDeadlineKind { ASSESSMENT, NOTIFICATION }

/** One tracked deadline. */
data class BreachDeadline(
    val kind: BreachDeadlineKind,
    val dueAt: Instant,
)

/** The result of computing the deadlines of a breach case. */
sealed interface BreachDeadlines {
    /** No terms were supplied, so no deadline exists. */
    data object NotConfigured : BreachDeadlines

    /** The notification is null until the case is assessed as notifiable. */
    data class Computed(
        val assessment: BreachDeadline,
        val notification: BreachDeadline?,
    ) : BreachDeadlines
}

/** Pure deadline computation on elapsed time; the clock is an argument. */
object BreachClock {
    fun compute(
        terms: BreachTerms?,
        suspicionRecordedAt: Instant,
        notifiableAssessedAt: Instant?,
    ): BreachDeadlines {
        if (terms == null) return BreachDeadlines.NotConfigured
        require(notifiableAssessedAt == null || !notifiableAssessedAt.isBefore(suspicionRecordedAt)) {
            "notifiableAssessedAt must not be before suspicionRecordedAt"
        }
        return BreachDeadlines.Computed(
            BreachDeadline(BreachDeadlineKind.ASSESSMENT, suspicionRecordedAt.plus(terms.assessmentWithin)),
            notifiableAssessedAt?.let {
                BreachDeadline(BreachDeadlineKind.NOTIFICATION, it.plus(terms.notificationWithin))
            },
        )
    }

    /** A deadline stated as "within" T is met at T, so it is overdue only after its due instant. */
    fun isOverdue(
        deadline: BreachDeadline,
        now: Instant,
    ): Boolean = now.isAfter(deadline.dueAt)
}
