package com.dynokore.gst.gatewayauthz.ratelimit

/**
 * The route classes (coordinator ruling INV-Q-4083): set per route by the api-gateway chart (INV-T-3990) from its
 * contract-generated route table (API-26) and received as the extAuth context value [wire]. A request whose class is
 * missing or unknown is refused (fail closed).
 */
enum class RouteClass(
    val wire: String,
) {
    /** The machine public API (API-23): a bearer token is required. */
    MACHINE_API("machine-api"),

    /** The console routes: no token check and no limit at the gateway (owner ruling INV-Q-4085 A). */
    CONSOLE("console"),

    /** The signed-link pages (FR-AF.25): no bearer token at the gateway, limited per link. */
    SIGNED_LINK("signed-link"),

    /** The AP notification route (API-27): no bearer token at the gateway, limited per `{adapterId}`. */
    AP_NOTIFICATION("ap-notification"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(value: String?): RouteClass? = value?.let { byWire[it] }
    }
}
