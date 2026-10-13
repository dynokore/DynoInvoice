package com.dynokore.platform.serviceruntime.identity

/**
 * A signed workload token, minted from this pod's projected Kubernetes
 * service-account token (STK-26, INV-REQ-1202): opaque to this module — it is
 * minted and rotated by the cluster, never by this code — and its value is
 * never logged (CS-34): [toString] deliberately does not return [value].
 */
@JvmInline
value class WorkloadToken(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "WorkloadToken must not be blank" }
    }

    override fun toString(): String = "WorkloadToken(***)"

    companion object {
        /** The header a workload token travels on — distinct from `Authorization`,
         * the propagated end-user token (INV-REQ-1202). */
        const val HEADER_NAME = "X-Workload-Token"
    }
}
