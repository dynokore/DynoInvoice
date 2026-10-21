package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.ap.IrasEnvironment
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

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
) {
    /** One adapter: its id in the attempt log and the path segment the AP names it by. */
    data class Adapter(
        val id: String,
        val path: String,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 25L
    }
}
