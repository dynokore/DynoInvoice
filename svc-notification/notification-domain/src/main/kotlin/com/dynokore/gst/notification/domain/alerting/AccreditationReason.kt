package com.dynokore.gst.notification.domain.alerting

const val CLASS_ACCREDITATION = "ACCREDITATION"
const val PLATFORM_ENTITY_REF = "ffffffff-ffff-ffff-ffff-ffffffffffff"
const val ROLE_RELEASE_MANAGER = "release.manager"
const val ACCREDITATION_ALERT_TEMPLATE = "ACCREDITATION_ALERT"

/** Why the release pipeline raised an accreditation alert (INV-REQ-2657, INV-REQ-1365). */
enum class AccreditationReason {
    ACCREDITATION_LAPSED,
    REASSESSMENT_NEEDED,
    ;

    companion object {
        /** The member named exactly [key]; any other string (lower case, look-alike, empty) is refused. */
        fun fromKey(key: String): AccreditationReason =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("unknown accreditation reason key: '$key'")
    }
}
