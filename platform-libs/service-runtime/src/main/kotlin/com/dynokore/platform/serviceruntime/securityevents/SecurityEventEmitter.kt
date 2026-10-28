package com.dynokore.platform.serviceruntime.securityevents

/**
 * The one component of the shared service runtime that writes security-event log records (INV-REQ-1384, INV-Q-10945
 * option A): no service writes its own. The record is an OTLP/HTTP JSON log record sent to the telemetry endpoint
 * (INV-Q-10939).
 */
interface SecurityEventEmitter {
    /**
     * Hands one record of [securityClass] to the sender. Never throws and never waits for the collector: a record the
     * bounded queue cannot take, or that exhausts its attempts, is counted and logged as dropped.
     *
     * @param actor the acting user ID, never a role name (INV-Q-10647).
     * @param tenantId the tenant the event belongs to.
     */
    fun emit(
        securityClass: SecurityClass,
        actor: String,
        tenantId: String,
    )
}
