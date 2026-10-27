package com.dynokore.gst.notification.domain.notice

/** VP-12 option keys (INV-REQ-1273): how the client hears of a filing fact recorded by the agent. */
enum class NoticeOption {
    IMMEDIATE_NOTICE,
    ACKNOWLEDGED_NOTICE,
    DAILY_DIGEST,
    ;

    companion object {
        /** The member named exactly [key]; any other string (lower case, look-alike, empty) is refused. */
        fun fromKey(key: String): NoticeOption =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("unknown notice option key: '$key'")
    }
}
