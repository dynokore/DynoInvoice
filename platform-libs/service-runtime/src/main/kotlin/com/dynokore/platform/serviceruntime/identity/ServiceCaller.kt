package com.dynokore.platform.serviceruntime.identity

/**
 * The calling service of the current request, read from the verified workload token (API-25; INV-AC-8251): the
 * `service` of the configured `service-runtime.workload-identity.callers` entry whose `subject` equals the verified
 * subject. Null when there is no request in scope or the verified subject is not mapped. The identity is never read
 * from a header. The type has no method that takes a service name: the allowed callers of an operation come from the
 * contract (`x-sync-edge`) and are enforced by service-runtime, so a service writes no caller name.
 */
interface ServiceCaller {
    fun current(): String?

    companion object {
        /** The request attribute the workload filter sets for a mapped caller. */
        const val REQUEST_ATTRIBUTE = "com.dynokore.platform.serviceruntime.identity.ServiceCaller"
    }
}
