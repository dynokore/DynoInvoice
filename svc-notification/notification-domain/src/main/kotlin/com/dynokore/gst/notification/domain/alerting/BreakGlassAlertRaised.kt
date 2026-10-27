package com.dynokore.gst.notification.domain.alerting

private const val MAX_UNLOCK_REF = 64
private val ACC_CODE = Regex("^ACC-[0-9]{3}$")

/** Payload of the event BreakGlassAlertRaised. */
data class BreakGlassAlertRaised(
    val accCode: String,
    val scope: BreakGlassScope,
    val action: BreakGlassAction,
    val unlockRef: String?,
) {
    init {
        require(ACC_CODE.matches(accCode)) { "accCode must match ^ACC-[0-9]{3}$" }
        unlockRef?.let {
            require(it.isNotBlank() && it.length <= MAX_UNLOCK_REF) {
                "unlockRef must be 1..$MAX_UNLOCK_REF characters and not blank"
            }
        }
    }
}
