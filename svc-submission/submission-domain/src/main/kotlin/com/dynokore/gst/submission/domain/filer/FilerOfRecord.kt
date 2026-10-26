package com.dynokore.gst.submission.domain.filer

/** The VP-11 options of the entity (INV-REQ-1272); the keys are those of the catalogue row. */
enum class FilerOption {
    DERIVED,
    ALWAYS_CLIENT,
    EITHER_PARTY,
    ;

    companion object {
        /** The member named exactly [key]; any other string, including a different case, is refused. */
        fun fromKey(key: String): FilerOption =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("Unknown filer option key: '$key'")
    }
}

/** The two parties that can file the return. */
enum class Party { AGENT, CLIENT }

/**
 * @property named the filer of record; someone is always named (INV-REQ-915).
 * @property eitherMayRecord whether the other party may record the filing too.
 */
data class FilerDecision(
    val named: Party,
    val eitherMayRecord: Boolean,
)

/** Whom the System names as filer of record under each option (INV-REQ-915, INV-REQ-1272). */
object FilerOfRecord {
    fun decide(
        option: FilerOption,
        agentHoldsFileGstReturn: Boolean,
    ): FilerDecision {
        val derived = if (agentHoldsFileGstReturn) Party.AGENT else Party.CLIENT
        return when (option) {
            FilerOption.DERIVED -> FilerDecision(derived, false)
            FilerOption.ALWAYS_CLIENT -> FilerDecision(Party.CLIENT, false)
            FilerOption.EITHER_PARTY -> FilerDecision(derived, true)
        }
    }
}
