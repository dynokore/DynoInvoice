package com.dynokore.gst.notification.domain.notice

import java.time.Instant
import java.time.ZoneId

/** INV-REQ-1273 / INV-REQ-916: only a fact the agent recorded is notified; the effect is not an input. */
object FilingNoticeRoute {
    private val SINGAPORE: ZoneId = ZoneId.of("Asia/Singapore")

    fun decide(
        option: NoticeOption,
        recordedByAgent: Boolean,
        recordedOn: Instant,
    ): NoticeRoute =
        when {
            !recordedByAgent -> {
                NoticeRoute.NoNotice
            }

            else -> {
                when (option) {
                    NoticeOption.IMMEDIATE_NOTICE -> NoticeRoute.SendNow(requiresAcknowledgement = false)
                    NoticeOption.ACKNOWLEDGED_NOTICE -> NoticeRoute.SendNow(requiresAcknowledgement = true)
                    NoticeOption.DAILY_DIGEST -> NoticeRoute.AddToDigest(recordedOn.atZone(SINGAPORE).toLocalDate())
                }
            }
        }
}
