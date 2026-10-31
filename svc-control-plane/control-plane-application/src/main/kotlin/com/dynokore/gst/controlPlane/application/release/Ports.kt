package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.GoldenDocument
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.SupportWindow
import com.dynokore.gst.controlPlane.domain.release.TrainEvidence
import com.dynokore.gst.controlPlane.domain.release.TrainVersion

/**
 * The internal quality evidence of a train: scenario, regeneration, security and certification results, each with the
 * released version's re-run. Null when the train has no evidence, which holds the release back (fail closed). The
 * binding is a Wave 3 item (the certification runner); until bound, nothing is released.
 */
fun interface TrainEvidenceSource {
    fun forTrain(version: TrainVersion): TrainEvidence?
}

interface TrainRepository {
    fun find(version: TrainVersion): ReleaseTrain?

    fun save(train: ReleaseTrain)
}

interface SupportWindowRepository {
    fun find(windowVersion: String): SupportWindow?

    fun save(window: SupportWindow)
}

/**
 * Opens the re-accreditation assessment of a train: raises the alert and the tracked task (INV-REQ-1365). It is never a
 * release gate: a release is not held back by the result or by the absence of a task.
 */
fun interface AccreditationTasks {
    fun open(train: TrainVersion): AccreditationCheck
}

/** The release record of a pack version (INV-AC-2674): gate runs, classification, the role that classified. */
interface PackReleaseRecords {
    fun find(ref: PackRef): ReleaseRecord?

    fun save(
        ref: PackRef,
        record: ReleaseRecord,
    )
}

/**
 * The rule conformance corpus (INV-REQ-2660): the golden documents and the classification a pack gives each. [classify]
 * is null when the pack gives no classification. The corpus is unbound until Wave 3 supplies the documents of R1's
 * worked examples and Annexes A to C, so an empty corpus refuses every signature (fail closed).
 */
interface ConformanceCorpus {
    fun documents(): List<GoldenDocument>

    fun classify(
        pack: RegulatoryPack,
        document: GoldenDocument,
    ): String?
}
