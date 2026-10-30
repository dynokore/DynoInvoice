package com.dynokore.gst.payload.domain

import com.dynokore.platform.integrationcanon.AcceptanceCalendar
import com.dynokore.platform.integrationcanon.SpecVersion
import com.dynokore.platform.integrationcanon.VersionSelection
import com.dynokore.platform.integrationcanon.selectVersion
import java.time.LocalDate

/** The release chosen for a submission, carried beside the family and never inside it (INV-REQ-590). */
data class SubmissionRelease(
    val family: SpecificationFamily,
    val release: SpecVersion,
)

/** The outcome of [SubmissionReleaseChoice.choose]. */
sealed interface ReleaseChoice {
    data class Chosen(
        val submission: SubmissionRelease,
    ) : ReleaseChoice

    /** Nothing accepted on [submissionDate] is [notOlderThan] or newer; [acceptedOnDate] was accepted that day. */
    data class NoAcceptedRelease(
        val family: SpecificationFamily,
        val notOlderThan: SpecVersion,
        val submissionDate: LocalDate,
        val acceptedOnDate: List<SpecVersion>,
    ) : ReleaseChoice
}

/**
 * The oldest release accepted on the submission date that is not older than the transaction-date release
 * (INV-REQ-590). The rule lives once, in the platform selection; both dates are arguments and no clock is read
 * (INV-BR-215).
 */
object SubmissionReleaseChoice {
    fun choose(
        family: SpecificationFamily,
        calendar: AcceptanceCalendar,
        transactionDateRelease: SpecVersion,
        submissionDate: LocalDate,
    ): ReleaseChoice =
        when (val selection = selectVersion(calendar, transactionDateRelease, submissionDate)) {
            is VersionSelection.Selected -> {
                ReleaseChoice.Chosen(SubmissionRelease(family, selection.version))
            }

            is VersionSelection.NoAcceptedVersion -> {
                ReleaseChoice.NoAcceptedRelease(
                    family,
                    selection.notOlderThan,
                    selection.submissionDate,
                    selection.acceptedOnDate,
                )
            }
        }
}
