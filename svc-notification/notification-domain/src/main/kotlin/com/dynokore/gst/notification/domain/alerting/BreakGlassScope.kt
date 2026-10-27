package com.dynokore.gst.notification.domain.alerting

const val TRIGGER_BREAK_GLASS_USED = "BREAK_GLASS_USED"
const val ACC_BREAK_GLASS = "ACC-002"
const val BREAK_GLASS_TEMPLATE = "BREAK_GLASS_ALERT"

/** Where a break-glass use applies (INV-Q-8966 A, INV-REQ-897). */
enum class BreakGlassScope {
    TENANT,
    INSTALLATION,
    ;

    companion object {
        /** The member named exactly [key]; any other string (lower case, look-alike, empty) is refused. */
        fun fromKey(key: String): BreakGlassScope =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("unknown break-glass scope key: '$key'")
    }
}

/** What was done under break-glass (INV-Q-8966 A, INV-REQ-897). */
enum class BreakGlassAction {
    UNLOCK,
    VIEW,
    READINESS,
    DRAIN,
    REPLAY,
    ;

    companion object {
        /** The member named exactly [key]; any other string (lower case, look-alike, empty) is refused. */
        fun fromKey(key: String): BreakGlassAction =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("unknown break-glass action key: '$key'")
    }
}
