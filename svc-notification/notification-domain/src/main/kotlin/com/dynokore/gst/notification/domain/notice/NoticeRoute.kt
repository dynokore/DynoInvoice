package com.dynokore.gst.notification.domain.notice

import java.time.LocalDate

/** How a filing fact reaches the client; never a delay of the effect (INV-REQ-998). */
sealed interface NoticeRoute {
    data object NoNotice : NoticeRoute

    data class SendNow(
        val requiresAcknowledgement: Boolean,
    ) : NoticeRoute

    /** Added to the digest of the Singapore day [coversDate]. */
    data class AddToDigest(
        val coversDate: LocalDate,
    ) : NoticeRoute
}
