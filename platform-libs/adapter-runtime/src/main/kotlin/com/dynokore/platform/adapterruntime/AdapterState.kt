package com.dynokore.platform.adapterruntime

/**
 * The lifecycle state of one [ManagedAdapter] instance (INV-REQ-1354). `when` over
 * this enum is exhaustive with no `else` (CS-08).
 */
enum class AdapterState {
    /** Constructed, not yet accepting work. */
    STARTING,

    /** Accepting new work. */
    RUNNING,

    /** No longer accepting new work; existing in-flight work is finishing. */
    DRAINING,

    /** Drained (or forced past its drain deadline) and stopped. */
    STOPPED,

    /** Failed to start or failed irrecoverably while running. */
    FAILED,
}
