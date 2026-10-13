package com.dynokore.platform.serviceruntime.identity

/**
 * The service's workload principal, the `app.actor` of its background work (INV-REQ-854 b1, ARC-21; INV-ADR-3968 item
 * 6 format; owner ruling INV-Q-4344 (b)). Built from the required `gst.service` and format-checked: a lower-case
 * service name such as `submission-svc`, refused otherwise.
 *
 * - [value], `svc:<svc>`: service-level background work (relay, consumers, claims, announce, connection status,
 *   purges).
 * - [job], `svc:<svc>/job:<job>`: one job's runs, bound by the job runtime.
 */
class WorkloadPrincipal(
    val service: String,
) {
    init {
        require(SERVICE.matches(service)) { "gst.service must be a lower-case service name like 'submission-svc'" }
    }

    /** `svc:<svc>`. */
    val value: String = "$PREFIX$service"

    /** `svc:<svc>/job:<job>` for the runs of [job] (a job name as `JobDefinition` accepts it). */
    fun job(job: String): String {
        require(JOB.matches(job)) { "job name must be lower-case words like 'scheduled-despatch'" }
        return "$value/job:$job"
    }

    override fun equals(other: Any?): Boolean = other is WorkloadPrincipal && other.service == service

    override fun hashCode(): Int = service.hashCode()

    override fun toString(): String = value

    private companion object {
        const val PREFIX = "svc:"

        /** The service-name rule of `JobDefinition.ownerService`. */
        val SERVICE = Regex("^[a-z][a-z-]{0,62}$")

        /** The job-name rule of `JobDefinition.name`. */
        val JOB = Regex("^[a-z][a-z0-9-]{0,62}$")
    }
}
