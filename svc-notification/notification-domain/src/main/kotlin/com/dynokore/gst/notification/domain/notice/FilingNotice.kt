package com.dynokore.gst.notification.domain.notice

import java.time.LocalDate

/** The whole content of the client notice of an agent's filing fact (INV-REQ-916): no invoice content. */
data class FilingNotice(
    val filingDate: LocalDate,
    val filingReference: String,
    val lateDocumentCount: Int,
) {
    init {
        require(filingReference.isNotBlank()) { "filing reference must not be blank" }
        require(lateDocumentCount >= 0) { "late document count must be at least 0: $lateDocumentCount" }
    }
}
