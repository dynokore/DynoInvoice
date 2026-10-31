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

/** What a gate result must satisfy to be recorded (the 422 rules of recordGateRun). */
object GateRunRules {
    /** The gates a caller records; CORPUS and STAGING are produced by the service itself. */
    val RECORDABLE = setOf(Gate.SCENARIOS, Gate.REGENERATION, Gate.SECURITY, Gate.CERTIFICATION)
    private val PER_ENVIRONMENT = setOf(Gate.SCENARIOS, Gate.CERTIFICATION)
    const val MAX_FINDINGS = 500
    const val MAX_TEXT = 256

    /** The first rule the result breaks, or null. */
    @Suppress("CyclomaticComplexMethod")
    fun violation(
        gate: Gate,
        environment: ReferenceEnvironment?,
        candidate: GateOutcome,
        baseline: GateOutcome?,
        candidateFindings: List<String>,
        baselineFindings: List<String>,
        evidenceRef: String?,
    ): String? =
        when {
            gate !in RECORDABLE -> {
                "gate $gate is not recordable"
            }

            gate in PER_ENVIRONMENT && environment == null -> {
                "environment is required for $gate"
            }

            gate !in PER_ENVIRONMENT && environment != null -> {
                "environment is not accepted for $gate"
            }

            candidate == GateOutcome.PASSED && (baseline != null || baselineFindings.isNotEmpty()) -> {
                "baselineResult and baselineFindings are sent only with a FAILED candidateResult"
            }

            candidateFindings.size > MAX_FINDINGS || baselineFindings.size > MAX_FINDINGS -> {
                "at most $MAX_FINDINGS findings"
            }

            (candidateFindings + baselineFindings).any { it.isBlank() || it.length > MAX_TEXT } -> {
                "a finding is 1 to $MAX_TEXT characters"
            }

            evidenceRef != null && (evidenceRef.isBlank() || evidenceRef.length > MAX_TEXT) -> {
                "evidenceRef is 1 to $MAX_TEXT characters"
            }

            else -> {
                null
            }
        }
}
