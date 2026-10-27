package com.dynokore.gst.payload.domain

/** The result of recording one sample: the new latest-result map, the entity's finding flag and whether it flipped. */
data class RenditionStanding(
    val latest: Map<Pair<String, SubmissionType>, SampleResult>,
    val open: Boolean,
    val changed: Boolean,
)

/**
 * The entity's PAY-014 standing (INV-REQ-1033, INV-Q-6321): open exactly while the latest sample of any
 * (scenario, submission type) pair has failed. The finding is the entity's; no document is an argument.
 */
object EntityRenditionStanding {
    fun record(
        previous: Map<Pair<String, SubmissionType>, SampleResult>,
        scenario: String,
        submissionType: SubmissionType,
        result: SampleResult,
    ): RenditionStanding {
        val latest = previous + ((scenario to submissionType) to result)
        val open = latest.values.any { it == SampleResult.FAILED }
        val wasOpen = previous.values.any { it == SampleResult.FAILED }
        return RenditionStanding(latest, open, open != wasOpen)
    }
}
