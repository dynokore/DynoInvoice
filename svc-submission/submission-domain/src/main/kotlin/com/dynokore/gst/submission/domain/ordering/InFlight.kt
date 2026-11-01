package com.dynokore.gst.submission.domain.ordering

/**
 * The states in which a version of a Doc UUID is in flight (INV-REQ-571 behaviour 1): `QUEUED`, `SENT`,
 * `SENT_UNCONFIRMED` and `RETRY`. One constant shared by the enqueue guard, the release step and the migration test;
 * the partial unique index `submission_in_flight_uq` holds the same four names.
 */
object InFlight {
    val STATES: Set<LifecycleState> =
        setOf(LifecycleState.QUEUED, LifecycleState.SENT, LifecycleState.SENT_UNCONFIRMED, LifecycleState.RETRY)

    val NAMES: Set<String> = STATES.mapTo(linkedSetOf()) { it.name }

    /** The name of the partial unique index (INV-REQ-571); a unique violation of it is an in-flight breach. */
    const val INDEX_NAME = "submission_in_flight_uq"

    /** The catalogue code of an attempted breach (catalogue/errors/error-codes.yaml, BLOCK, TERMINAL; INV-REQ-548). */
    const val SUB_008 = "SUB-008"

    fun isInFlight(state: LifecycleState): Boolean = state in STATES
}
