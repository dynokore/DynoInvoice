package com.dynokore.gst.controlPlane.domain.release

import com.dynokore.gst.controlPlane.domain.pack.ActorId
import java.time.Instant

/**
 * One recorded gate result of a release train (INV-REQ-2657, recordGateRun): one record per train, gate and
 * environment, append-only. [recordedBy] is the user ID of the caller, never a role (INV-Q-10647). The environment
 * is held for SCENARIOS and CERTIFICATION and absent for REGENERATION and SECURITY; for REGENERATION the findings
 * are the identifiers of the documents whose classification differs.
 */
data class GateRunRecord(
    val trainVersion: TrainVersion,
    val gate: Gate,
    val environment: ReferenceEnvironment?,
    val candidate: GateOutcome,
    val baseline: GateOutcome?,
    val candidateFindings: Set<String>,
    val baselineFindings: Set<String>,
    val evidenceRef: String?,
    val recordedBy: ActorId,
    val recordedAt: Instant,
) {
    /** The train, gate and environment: the key a second record may not take. */
    val key: GateRunKey get() = GateRunKey(trainVersion, gate, environment)

    /** Whether [other] says the same thing as this record, whoever recorded it and whenever. */
    fun sameResultAs(other: GateRunRecord) =
        key == other.key &&
            candidate == other.candidate &&
            baseline == other.baseline &&
            candidateFindings == other.candidateFindings &&
            baselineFindings == other.baselineFindings &&
            evidenceRef == other.evidenceRef
}

data class GateRunKey(
    val trainVersion: TrainVersion,
    val gate: Gate,
    val environment: ReferenceEnvironment?,
)

/** A rule of recordGateRun a submission breaks (HTTP 422, CPL-006): the field of the request and the message. */
data class GateRunViolation(
    val field: String,
    val message: String,
)

/**
 * A gate result as a caller submits it, before it is stored. The shape limits of the contract (at most 500 findings,
 * a finding or `evidenceRef` of 1 to 256 characters) are enforced on construction: a breach is an
 * [IllegalArgumentException], which the HTTP edge answers as a shape violation (400 SRT-001), never as a gate rule.
 */
data class GateRunSubmission(
    val gate: Gate,
    val environment: ReferenceEnvironment?,
    val candidate: GateOutcome,
    val baseline: GateOutcome? = null,
    val candidateFindings: List<String> = emptyList(),
    val baselineFindings: List<String> = emptyList(),
    val evidenceRef: String? = null,
) {
    init {
        require(candidateFindings.size <= MAX_FINDINGS) { "candidateFindings holds at most $MAX_FINDINGS entries" }
        require(baselineFindings.size <= MAX_FINDINGS) { "baselineFindings holds at most $MAX_FINDINGS entries" }
        require((candidateFindings + baselineFindings).none { it.isBlank() || it.length > MAX_TEXT }) {
            "a finding is 1 to $MAX_TEXT characters"
        }
        require(evidenceRef == null || (evidenceRef.isNotBlank() && evidenceRef.length <= MAX_TEXT)) {
            "evidenceRef is 1 to $MAX_TEXT characters"
        }
    }

    /** The first gate rule of recordGateRun (HTTP 422, CPL-006) the submission breaks, or null. */
    fun violation(): GateRunViolation? =
        when {
            gate !in RECORDABLE -> {
                GateRunViolation("/gate", "gate $gate is not recordable")
            }

            gate in PER_ENVIRONMENT && environment == null -> {
                GateRunViolation("/environment", "environment is required for $gate")
            }

            gate !in PER_ENVIRONMENT && environment != null -> {
                GateRunViolation("/environment", "environment is not accepted for $gate")
            }

            candidate == GateOutcome.PASSED && baseline != null -> {
                GateRunViolation("/baselineResult", "baselineResult is sent only with a FAILED candidateResult")
            }

            candidate == GateOutcome.PASSED && baselineFindings.isNotEmpty() -> {
                GateRunViolation("/baselineFindings", "baselineFindings are sent only with a FAILED candidateResult")
            }

            else -> {
                null
            }
        }

    companion object {
        /** The gates a caller records; CORPUS and STAGING are produced by the service itself. */
        val RECORDABLE = setOf(Gate.SCENARIOS, Gate.REGENERATION, Gate.SECURITY, Gate.CERTIFICATION)
        private val PER_ENVIRONMENT = setOf(Gate.SCENARIOS, Gate.CERTIFICATION)
        const val MAX_FINDINGS = 500
        const val MAX_TEXT = 256
    }
}
