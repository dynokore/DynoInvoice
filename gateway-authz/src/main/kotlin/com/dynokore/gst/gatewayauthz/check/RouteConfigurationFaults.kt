package com.dynokore.gst.gatewayauthz.check

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory

/**
 * Route configuration faults (coordinator ruling INV-Q-4095): a request whose route class is missing or unknown, or
 * whose path does not match its route's path template, is a platform configuration fault. Each one is logged at error
 * level with the route and counted on [METRIC], which drives the alert.
 */
class RouteConfigurationFaults(
    registry: MeterRegistry?,
) {
    private val log = LoggerFactory.getLogger(RouteConfigurationFaults::class.java)
    private val counter: Counter? = registry?.let { Counter.builder(METRIC).register(it) }

    fun record(request: AuthzRequest) {
        log.error(
            "route configuration fault: route-class={} path-template={} path={}",
            request.context[AuthzRequest.ROUTE_CLASS],
            request.context[AuthzRequest.PATH_TEMPLATE],
            request.path.substringBefore('?'),
        )
        counter?.increment()
    }

    companion object {
        const val METRIC = "gateway.authz.route.configuration.faults"
    }
}
