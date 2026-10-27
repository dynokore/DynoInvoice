package com.dynokore.gst.notification.domain.alerting

const val PRIVACY_NOTICE_TEMPLATE = "PRIVACY_NOTICE_DUE"
const val TENANT_WIDE_ENTITY_REF = "00000000-0000-0000-0000-000000000000"
const val TRIGGER_PRIVACY_NOTICE_DUE = "PRIVACY_NOTICE_DUE"
const val CLASS_SECURITY = "SECURITY"

/** The kind of a privacy request case (INV-REQ-1403). */
enum class PrivacyRequestKind {
    ACCESS,
    CORRECTION,
    ;

    companion object {
        /** The member named exactly [key]; any other string (lower case, look-alike, empty) is refused. */
        fun fromKey(key: String): PrivacyRequestKind =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("unknown privacy request kind key: '$key'")
    }
}
