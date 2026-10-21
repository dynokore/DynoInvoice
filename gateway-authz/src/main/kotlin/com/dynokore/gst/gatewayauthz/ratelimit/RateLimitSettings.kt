package com.dynokore.gst.gatewayauthz.ratelimit

/**
 * The protective limits per 60-second window (INV-REQ-825 b5, owner ruling INV-Q-4048 A), installation settings with
 * these defaults: the machine public API 600 per principal and 1,200 per tenant; the signed-link pages 30 per link. The
 * console limits are console-bff's (owner ruling INV-Q-4085 A, INV-T-4109), not the gateway's. The API limits may be
 * raised or lowered; the signed-link limit may only be lowered (tighten-only: it guards public pages), so a value above
 * 30 is refused.
 */
data class RateLimitSettings(
    val machinePerPrincipal: Int = DEFAULT_MACHINE_PER_PRINCIPAL,
    val machinePerTenant: Int = DEFAULT_MACHINE_PER_TENANT,
    val signedLinkPerLink: Int = DEFAULT_SIGNED_LINK_PER_LINK,
) {
    init {
        listOf(machinePerPrincipal, machinePerTenant, signedLinkPerLink)
            .forEach { require(it >= 1) { "a protective limit must be at least 1 request per window (INV-REQ-825)" } }
        require(signedLinkPerLink <= DEFAULT_SIGNED_LINK_PER_LINK) {
            "the signed-link page limit may only be lowered: $signedLinkPerLink is above the default " +
                "$DEFAULT_SIGNED_LINK_PER_LINK (tighten-only, INV-REQ-825 b5)"
        }
    }

    companion object {
        const val DEFAULT_MACHINE_PER_PRINCIPAL = 600
        const val DEFAULT_MACHINE_PER_TENANT = 1_200
        const val DEFAULT_SIGNED_LINK_PER_LINK = 30
    }
}
