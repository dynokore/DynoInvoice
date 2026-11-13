package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * The owning services console-bff calls synchronously (TPL-07, ARC-08): the edge name (the `x-sync-edge` of the
 * owner's contract), the property that holds the base URL and the edge timeout (the `x-timeout` of the operation).
 */
enum class OwningService(
    val edge: String,
    val urlProperty: String,
    val timeout: Duration,
) {
    PAYLOAD("console-bff->payload-svc", "console.payload-svc-url", Duration.ofMillis(2000)),
    SUBMISSION("console-bff->submission-svc", "console.submission-svc-url", Duration.ofMillis(2000)),
}

/** The one place that names the edges, base URLs and timeouts of the owning-service clients. */
object OwningServiceEdge {
    private const val PLACEHOLDER_START = "\${"

    /** Required, not blank and not an unresolved placeholder, with no default (TPL-16, twelve-factor config). */
    fun baseUrl(
        environment: Environment,
        service: OwningService,
    ): String =
        environment.getRequiredProperty(service.urlProperty).also {
            require(it.isNotBlank()) { "${service.urlProperty} must not be blank" }
            require(!it.startsWith(PLACEHOLDER_START)) { "${service.urlProperty} is an unresolved placeholder" }
        }

    fun restClient(
        builder: RestClient.Builder,
        environment: Environment,
        service: OwningService,
    ): RestClient = builder.baseUrl(baseUrl(environment, service)).build()

    fun policy(
        registry: ResilienceRegistry,
        service: OwningService,
    ): ResilientEdge = registry.forEdge(service.edge)
}
