package com.dynokore.platform.serviceruntime.tracing

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `gst.tracing.peers`: the map from the host of an outbound call to the service id of the callee. A URL host is not a
 * service id (the Kubernetes Service name is `<release>-<serviceName>`), so the deployment names the pairs.
 */
@ConfigurationProperties(prefix = "gst.tracing")
data class TracingProperties(
    val peers: List<Peer> = emptyList(),
) {
    data class Peer(
        val host: String = "",
        val service: String = "",
    )
}
