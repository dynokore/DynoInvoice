package com.dynokore.gst.notification.domain.filing

/** VP-16 option keys (INV-REQ-1277): when the filer of record is prompted to record the filing fact. */
enum class PromptOption {
    READY_AND_3_DAYS,
    CUSTOM_DAYS,
    ;

    companion object {
        /** The member named exactly [key]; any other string (lower case, look-alike, empty) is refused. */
        fun fromKey(key: String): PromptOption =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("unknown prompt option key: '$key'")
    }
}
