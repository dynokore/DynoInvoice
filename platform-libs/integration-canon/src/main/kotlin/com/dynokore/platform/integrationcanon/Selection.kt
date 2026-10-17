package com.dynokore.platform.integrationcanon

import java.time.LocalDate

/** The outcome of [selectVersion]. */
sealed interface VersionSelection {
    data class Selected(
        val version: SpecVersion,
    ) : VersionSelection

    /** Nothing accepted on [submissionDate] is [notOlderThan] or newer; [acceptedOnDate] was accepted that day. */
    data class NoAcceptedVersion(
        val notOlderThan: SpecVersion,
        val submissionDate: LocalDate,
        val acceptedOnDate: List<SpecVersion>,
    ) : VersionSelection
}

/**
 * The oldest version accepted on [submissionDate] that is not older than [notOlderThan]. It never falls back to an
 * older version and never to a default. The host resolves [notOlderThan] from the transaction date; that date is not
 * an input here.
 */
fun selectVersion(
    calendar: AcceptanceCalendar,
    notOlderThan: SpecVersion,
    submissionDate: LocalDate,
): VersionSelection {
    require(calendar.order.contains(notOlderThan)) { "version $notOlderThan is not in the release order" }
    val accepted = calendar.acceptedOn(submissionDate)
    val chosen = accepted.firstOrNull { !calendar.order.isOlder(it, notOlderThan) }
    return if (chosen != null) {
        VersionSelection.Selected(chosen)
    } else {
        VersionSelection.NoAcceptedVersion(notOlderThan, submissionDate, accepted)
    }
}

/** The host's answer on whether a version can represent the content the document requires. */
sealed interface RepresentabilityAnswer {
    data object Representable : RepresentabilityAnswer

    data class Unrepresentable(
        val reason: String,
    ) : RepresentabilityAnswer
}

fun interface Representability {
    fun check(version: SpecVersion): RepresentabilityAnswer
}

/** The outcome of [selectRepresentable]. */
sealed interface RepresentableSelection {
    data class Selected(
        val version: SpecVersion,
    ) : RepresentableSelection

    data class NoAcceptedVersion(
        val notOlderThan: SpecVersion,
        val submissionDate: LocalDate,
        val acceptedOnDate: List<SpecVersion>,
    ) : RepresentableSelection

    /** The selected [version] cannot represent the content; no other version was tried and nothing was re-coded. */
    data class Blocked(
        val version: SpecVersion,
        val reason: String,
    ) : RepresentableSelection
}

/** [selectVersion], then ask [check] about the selected version only. */
fun selectRepresentable(
    calendar: AcceptanceCalendar,
    notOlderThan: SpecVersion,
    submissionDate: LocalDate,
    check: Representability,
): RepresentableSelection =
    when (val s = selectVersion(calendar, notOlderThan, submissionDate)) {
        is VersionSelection.NoAcceptedVersion -> {
            RepresentableSelection.NoAcceptedVersion(s.notOlderThan, s.submissionDate, s.acceptedOnDate)
        }

        is VersionSelection.Selected -> {
            when (val a = check.check(s.version)) {
                RepresentabilityAnswer.Representable -> RepresentableSelection.Selected(s.version)
                is RepresentabilityAnswer.Unrepresentable -> RepresentableSelection.Blocked(s.version, a.reason)
            }
        }
    }
