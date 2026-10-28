package com.dynokore.platform.serviceruntime.securityevents

/**
 * The closed set of security-event classes INV-REQ-1384 names, with the wire value each carries in the OTLP log record
 * (INV-Q-10939; the owner-approved requirement words). Everything else about the export is configuration
 * (`gst.security-events.*`).
 */
enum class SecurityClass(
    val wireValue: String,
) {
    SIGN_IN("sign_in"),
    ELEVATION("elevation"),
    BREAK_GLASS("break_glass"),
    CREDENTIAL_USE("credential_use"),
    CONTROL_RELAXATION("control_relaxation"),
    ;

    companion object {
        /** The class whose [wireValue] equals [value] exactly, or null. */
        fun fromWire(value: String): SecurityClass? = entries.firstOrNull { it.wireValue == value }
    }
}
