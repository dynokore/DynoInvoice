package com.dynokore.platform.adapterruntime

import java.time.Instant

/**
 * An adapter's own assessment of its external connection, in the vocabulary of the
 * §35.13 connection contract (the live requirement is the Keel text this module's
 * task carries; SDS §35.13 is archived and read only through it). Mirrors the health
 * statuses TPL-16 already uses at the service level (`DOWN`, `DEGRADED`, `UP`), plus
 * `UNKNOWN` before a first check has run.
 */
enum class ConnectionState {
    UP,
    DEGRADED,
    DOWN,
    UNKNOWN,
}

/**
 * One point-in-time connection report for one [AdapterVersion], as reported through
 * [ConnectionStatusReporter]. [detail] is a short, non-sensitive reason code — never
 * invoice content, a secret or a raw third-party exception message (CS-34).
 */
data class ConnectionStatus(
    val version: AdapterVersion,
    val state: ConnectionState,
    val checkedAt: Instant,
    val detail: String? = null,
)
