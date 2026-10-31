package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.controlPlane.domain.release.Certification
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GateRunRecord
import com.dynokore.gst.controlPlane.domain.release.Observation
import com.dynokore.gst.controlPlane.domain.release.RecordedRegeneration
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.TrainEvidence
import com.dynokore.gst.controlPlane.domain.release.TrainVersion

/**
 * The production binding of [TrainEvidenceSource] (INV-T-8749, INV-Q-8717 A): the evidence of a train is built from
 * the gate results recorded for it. Null, which holds the release back, unless a SCENARIOS and a CERTIFICATION record
 * exist for each reference environment and a SECURITY and a REGENERATION record exist.
 */
class RecordedTrainEvidence(
    private val runs: GateRunRepository,
) : TrainEvidenceSource {
    override fun forTrain(version: TrainVersion): TrainEvidence? {
        val records = runs.forTrain(version).filter { it.trainVersion == version }
        val scenarios = perEnvironment(records, Gate.SCENARIOS) ?: return null
        val certification = perEnvironment(records, Gate.CERTIFICATION) ?: return null
        val security = single(records, Gate.SECURITY) ?: return null
        val regeneration = single(records, Gate.REGENERATION) ?: return null
        return TrainEvidence(
            scenarios = merged(scenarios),
            security = observationOf(security),
            regeneration = RecordedRegeneration(observationOf(regeneration)),
            certification = Certification(certification.mapValues { it.value.candidate }),
            releasedCertification = releasedCertification(certification),
        )
    }

    private fun single(
        records: List<GateRunRecord>,
        gate: Gate,
    ) = records.singleOrNull { it.gate == gate && it.environment == null }

    private fun perEnvironment(
        records: List<GateRunRecord>,
        gate: Gate,
    ): Map<ReferenceEnvironment, GateRunRecord>? {
        val byEnvironment =
            records
                .filter { it.gate == gate && it.environment != null }
                .groupBy { checkNotNull(it.environment) }
        return ReferenceEnvironment.entries
            .associateWith { byEnvironment[it].orEmpty().singleOrNull() ?: return null }
    }

    private fun observationOf(record: GateRunRecord) =
        Observation(record.candidate, record.candidateFindings, record.baseline, record.baselineFindings)

    /** Passed only when every environment passed; the baseline is absent when no record holds one. */
    private fun merged(byEnvironment: Map<ReferenceEnvironment, GateRunRecord>): Observation {
        val all = byEnvironment.values
        val baselines = all.mapNotNull { it.baseline }
        return Observation(
            candidate = if (all.all { it.candidate == GateOutcome.PASSED }) GateOutcome.PASSED else GateOutcome.FAILED,
            findings = all.flatMapTo(LinkedHashSet()) { it.candidateFindings },
            baseline =
                when {
                    baselines.isEmpty() -> null
                    baselines.any { it == GateOutcome.FAILED } -> GateOutcome.FAILED
                    else -> GateOutcome.PASSED
                },
            baselineFindings = all.flatMapTo(LinkedHashSet()) { it.baselineFindings },
        )
    }

    /** The re-run against the released version, when any environment record holds one. */
    private fun releasedCertification(byEnvironment: Map<ReferenceEnvironment, GateRunRecord>): Certification? =
        byEnvironment.values
            .takeIf { records -> records.any { it.baseline != null } }
            ?.let { Certification(byEnvironment.mapNotNull { (env, rec) -> rec.baseline?.let { env to it } }.toMap()) }
}
