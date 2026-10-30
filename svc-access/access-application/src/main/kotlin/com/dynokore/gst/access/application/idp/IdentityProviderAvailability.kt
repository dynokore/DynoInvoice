package com.dynokore.gst.access.application.idp

/**
 * One probe of the identity provider (INV-Q-9111 C): fetches the OIDC discovery document and the key set and answers
 * true only when both answer 200 with a parseable body. A failure of any kind answers false; the port never throws.
 */
fun interface IdentityProviderProbe {
    fun probe(): Boolean
}

/**
 * Whether the identity provider of one installation is available (INV-Q-9111 C, INV-Q-11557 A): it counts as
 * unavailable after [failureLimit] consecutive failed probes and as available after one success; before the first
 * probe it counts as available. The component is per installation: it is bound to one [issuerUri] and shares no count.
 */
class IdentityProviderAvailability(
    val issuerUri: String,
    private val probe: IdentityProviderProbe,
    private val failureLimit: Int = DEFAULT_FAILURE_LIMIT,
) {
    init {
        require(failureLimit > 0) { "the failure limit must be positive" }
    }

    private var consecutiveFailures = 0

    /** Runs one probe and updates the count of consecutive failures; answers the availability after it. */
    @Synchronized
    fun probeOnce(): Boolean {
        val ok = runCatching { probe.probe() }.getOrDefault(false)
        consecutiveFailures = if (ok) 0 else minOf(consecutiveFailures + 1, failureLimit)
        return isAvailable()
    }

    @Synchronized
    fun isAvailable(): Boolean = consecutiveFailures < failureLimit

    companion object {
        const val DEFAULT_FAILURE_LIMIT = 3
    }
}
