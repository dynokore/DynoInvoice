package com.dynokore.platform.serviceruntime.identity

/**
 * This service's own workload identity, read fresh on every call (STK-26) —
 * never cached, so the cluster's own rotation of the projected token is visible
 * on the very next call, the same discipline `SecretStore` (STK-29) uses for
 * mounted secrets.
 */
fun interface WorkloadTokenSource {
    fun current(): WorkloadToken
}
