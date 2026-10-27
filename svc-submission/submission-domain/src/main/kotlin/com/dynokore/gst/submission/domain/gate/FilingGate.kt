package com.dynokore.gst.submission.domain.gate

/** Error code of a refused filing (PER-001, overridable). */
const val REFUSAL_CODE = "PER-001"

/** Error code of an override that has no block to lift (SRT-005). */
const val NO_BLOCK_CODE = "SRT-005"

/** Error code of an override for a check that is no longer the latest (SRT-008). */
const val STALE_CODE = "SRT-008"

/** The VP-14 options of the entity (INV-REQ-1275); the keys are those of the catalogue row. */
enum class GateMode {
    ADVISORY,
    BLOCKING,
    ;

    companion object {
        /** The member named exactly [key]; any other string, including a different case, is refused. */
        fun fromKey(key: String): GateMode =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("Unknown gate mode key: '$key'")
    }
}

/** The verdict values of a readiness check record in the submission contract. */
enum class CheckVerdict { READY, NOT_READY }

/** The latest readiness check of the period. */
data class LatestCheck(
    val checkId: String,
    val verdict: CheckVerdict,
) {
    init {
        require(checkId.isNotEmpty()) { "checkId must not be empty" }
    }
}

/** Why a filing proceeds. */
enum class ProceedBasis { ADVISORY, READY, OVERRIDDEN }

/** The outcome of [FilingGate.decide]. */
sealed interface GateDecision

data class Proceed(
    val basis: ProceedBasis,
) : GateDecision

data class Refuse(
    val code: String = REFUSAL_CODE,
) : GateDecision {
    init {
        require(code == REFUSAL_CODE) { "A refusal carries $REFUSAL_CODE" }
    }
}

/** The outcome of [FilingGate.canOverride]. */
sealed interface OverrideDecision

data object Allowed : OverrideDecision

data class NoBlock(
    val code: String = NO_BLOCK_CODE,
) : OverrideDecision {
    init {
        require(code == NO_BLOCK_CODE) { "NoBlock carries $NO_BLOCK_CODE" }
    }
}

data class Stale(
    val code: String = STALE_CODE,
) : OverrideDecision {
    init {
        require(code == STALE_CODE) { "Stale carries $STALE_CODE" }
    }
}

/** Whether a filing is refused in blocking mode and whether an override is allowed (INV-REQ-72, INV-REQ-1275). */
object FilingGate {
    fun decide(
        mode: GateMode,
        latestCheck: LatestCheck?,
        overriddenCheckId: String?,
    ): GateDecision {
        if (mode == GateMode.ADVISORY) return Proceed(ProceedBasis.ADVISORY)
        return when {
            latestCheck == null -> Refuse()
            latestCheck.verdict == CheckVerdict.READY -> Proceed(ProceedBasis.READY)
            overriddenCheckId == latestCheck.checkId -> Proceed(ProceedBasis.OVERRIDDEN)
            else -> Refuse()
        }
    }

    fun canOverride(
        mode: GateMode,
        latestCheck: LatestCheck?,
        knownCheckIds: Set<String>,
        requestedCheckId: String,
    ): OverrideDecision {
        require(requestedCheckId.isNotEmpty()) { "requestedCheckId must not be empty" }
        return when {
            mode == GateMode.ADVISORY -> NoBlock()
            requestedCheckId !in knownCheckIds -> NoBlock()
            latestCheck == null || requestedCheckId != latestCheck.checkId -> Stale()
            latestCheck.verdict == CheckVerdict.READY -> NoBlock()
            else -> Allowed
        }
    }
}
