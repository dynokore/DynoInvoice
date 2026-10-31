package com.dynokore.gst.controlPlane.domain.release

import com.dynokore.gst.controlPlane.domain.pack.ActorId
import java.time.Instant

@JvmInline
value class TrainVersion(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX) { "a train version is 1 to $MAX characters" }
    }

    override fun toString() = value

    private companion object {
        const val MAX = 32
    }
}

enum class TrainKind { TRAIN, PATCH }

/** The reference environments a train is certified in (INV-REQ-909 b1): SaaS and the private minimal profile. */
enum class ReferenceEnvironment { SAAS, PRIVATE_MINIMAL }

/**
 * The certification gate (INV-REQ-909): a train is certified when it passed in both reference environments. An
 * environment with no result is not certified.
 */
data class Certification(
    val results: Map<ReferenceEnvironment, GateOutcome>,
) {
    val outcome: GateOutcome
        get() =
            if (ReferenceEnvironment.entries.all { results[it] == GateOutcome.PASSED }) {
                GateOutcome.PASSED
            } else {
                GateOutcome.FAILED
            }

    /** The environments the train is not certified in, as finding ids. */
    val missing: Set<String>
        get() =
            ReferenceEnvironment.entries
                .filter { results[it] != GateOutcome.PASSED }
                .mapTo(LinkedHashSet()) { it.name }
}

/** What one gate observed on the candidate and, re-run, on the released version. */
data class Observation(
    val candidate: GateOutcome,
    val findings: Set<String> = emptySet(),
    val baseline: GateOutcome? = null,
    val baselineFindings: Set<String> = emptySet(),
)

/** Classifications before and after the engine change, for the candidate and for the released version. */
data class RegenerationObservation(
    val before: Map<DocumentId, String>,
    val after: Map<DocumentId, String>,
    val releasedBefore: Map<DocumentId, String>? = null,
    val releasedAfter: Map<DocumentId, String>? = null,
)

/**
 * The internal quality evidence of one train (INV-REQ-2657 b1): the scenarios against the simulators, the regeneration
 * invariant, the security checks and the certification. Nothing else enters the gates.
 */
data class TrainEvidence(
    val scenarios: Observation,
    val security: Observation,
    val regeneration: RegenerationObservation,
    val certification: Certification,
    val releasedCertification: Certification? = null,
) {
    fun runs(): List<GateRun> =
        listOf(
            scenarios.toRun(Gate.SCENARIOS),
            security.toRun(Gate.SECURITY),
            regenerationRun(),
            certificationRun(),
        )

    private fun Observation.toRun(gate: Gate) = GateRun(gate, candidate, findings, baseline, baselineFindings)

    private fun regenerationRun(): GateRun {
        val candidate = RegenerationCheck.run(regeneration.before, regeneration.after)
        val released =
            regeneration.releasedBefore?.let {
                RegenerationCheck.run(it, regeneration.releasedAfter.orEmpty())
            }
        return GateRun(
            Gate.REGENERATION,
            if (candidate.isEmpty()) GateOutcome.PASSED else GateOutcome.FAILED,
            candidate,
            released?.let { if (it.isEmpty()) GateOutcome.PASSED else GateOutcome.FAILED },
            released.orEmpty(),
        )
    }

    private fun certificationRun() =
        GateRun(
            Gate.CERTIFICATION,
            certification.outcome,
            certification.missing,
            releasedCertification?.outcome,
            releasedCertification?.missing.orEmpty(),
        )
}

/** The re-accreditation assessment opened for a train (INV-REQ-1365): a tracked task with an alert, never a gate. */
data class AccreditationCheck(
    val assessmentNeeded: Boolean,
    val alertRef: String?,
    val taskRef: String?,
)

/** What release.manager's release record holds: the gate runs, the classification, the acting user ID (INV-Q-10647). */
data class ReleaseRecord(
    val runs: List<GateRun>,
    val exposureReducing: Boolean,
    val classifiedBy: ActorId,
    val recordedAt: Instant,
)

/** A published train or patch train (INV-REQ-909 b2). */
data class ReleaseTrain(
    val version: TrainVersion,
    val kind: TrainKind,
    val exposureReducing: Boolean,
    val record: ReleaseRecord,
    val accreditation: AccreditationCheck,
)

/** The supported-version window (INV-REQ-1095): the train versions the platform supports. */
data class SupportWindow(
    val windowVersion: String,
    val trainVersions: List<TrainVersion>,
) {
    init {
        require(windowVersion.isNotBlank() && windowVersion.length <= MAX_WINDOW) {
            "windowVersion is 1 to $MAX_WINDOW characters"
        }
        require(trainVersions.size <= MAX_TRAINS) { "a window lists at most $MAX_TRAINS trains" }
    }

    private companion object {
        const val MAX_WINDOW = 16
        const val MAX_TRAINS = 20
    }
}
