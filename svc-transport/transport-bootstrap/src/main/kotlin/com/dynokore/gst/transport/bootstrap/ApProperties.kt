package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.ap.IrasEnvironment
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/** How an adapter's notifications reach transport-svc (INV-Q-9684): the AP calls back, or this service polls. */
enum class NotificationMode { CALLBACK, POLLING }

/**
 * The Access Point adapter configuration (INV-REQ-501, INV-REQ-539): the IRAS environment, its two endpoints and the
 * adapters of this installation. There is no default environment or endpoint: a missing value fails the start.
 */
@ConfigurationProperties("transport.ap")
data class ApProperties(
    val environment: IrasEnvironment,
    val sandboxEndpoint: URI? = null,
    val productionEndpoint: URI? = null,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    val adapters: List<Adapter> = emptyList(),
    val metricsWindow: Duration = Duration.ofHours(1),
    /** How often a POLLING adapter is polled (INV-Q-9684): no default, required when any adapter is POLLING. */
    val pollInterval: Duration? = null,
) {
    init {
        require(pollInterval != null || adapters.none { it.notifications == NotificationMode.POLLING }) {
            "transport.ap.poll-interval is required when an adapter has notifications POLLING"
        }
    }

    /** One adapter: its id in the attempt log, the path segment the AP names it by and how its notifications arrive. */
    data class Adapter(
        val id: String,
        val path: String,
        val notifications: NotificationMode = NotificationMode.CALLBACK,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 25L
    }
}
