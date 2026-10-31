package com.dynokore.gst.controlPlane.adapters.stage

/**
 * One run of the stage job (coordinator ruling INV-Q-8725): selects the candidates and runs [PackStage] over each.
 * The job body of `PackStageJob` is this and nothing else; no operation calls it.
 */
class PackStageRun(
    private val candidates: StageCandidates,
    private val stage: PackStage,
) {
    fun runOnce(): List<StageOutcome> = candidates.select().map(stage::run)
}
