package com.dynokore.gst.transport.adapters.ap

import java.net.URI

/** The IRAS environment an Access Point connection targets, selected by explicit configuration (INV-REQ-501). */
enum class IrasEnvironment { SANDBOX, PRODUCTION }

/**
 * The endpoint the adapters call, chosen by the configured environment alone. A sandbox configuration resolves to the
 * sandbox endpoint and cannot reach the production one: the production endpoint is not even read (INV-REQ-501
 * behaviour 2).
 */
class ApEndpoints(
    val environment: IrasEnvironment,
    private val sandbox: URI?,
    private val production: URI?,
) {
    fun active(): URI =
        when (environment) {
            IrasEnvironment.SANDBOX -> {
                requireNotNull(sandbox) { "transport.ap.sandbox-endpoint is not configured" }
            }

            IrasEnvironment.PRODUCTION -> {
                requireNotNull(
                    production,
                ) { "transport.ap.production-endpoint is not configured" }
            }
        }
}
