package com.dynokore.platform.outboxandqueue

/** The event catalogue the tests use: an excerpt of platform-contracts `event-log/topics.yaml`. */
object TestCatalogue {
    const val CANONICAL_VERSION_CREATED = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"
    const val PAYLOAD_READY = "com.dynokore.gst.payload.PayloadReady.v1"
    const val SUBMISSION_DESPATCHED = "com.dynokore.gst.submission.SubmissionDespatched.v1"
    const val SUBMISSION_ACKNOWLEDGED = "com.dynokore.gst.submission.SubmissionAcknowledged.v1"
    const val AUDIT_ENTRY_RECORDED = "com.dynokore.gst.platform.AuditEntryRecorded.v1"

    /** The parsed excerpt. */
    fun load(): EventCatalogue {
        val text =
            checkNotNull(
                TestCatalogue::class.java.getResource("/topics-excerpt.yaml"),
            ) { "topics-excerpt.yaml missing" }
                .readText()
        return TopicsYamlCatalogue.parse(text)
    }
}
