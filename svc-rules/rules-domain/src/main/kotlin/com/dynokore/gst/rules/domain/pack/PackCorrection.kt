package com.dynokore.gst.rules.domain.pack

import java.time.LocalDate

/** The pack that was armed wrongly (INV-REQ-962). */
data class BadPack(
    val packType: String,
    val version: String,
    val effectiveFrom: LocalDate,
    val status: PackStatus,
)

/** The latest earlier pack of the type that the bad pack superseded; its content is what the correction restores. */
data class EarlierPack(
    val version: String,
    val contentRef: String,
)

/** The superseding pack: the bad pack's date, its version plus `-rollback`, the earlier pack's content. */
data class SupersedingSpec(
    val packType: String,
    val version: String,
    val effectiveFrom: LocalDate,
    val contentRef: String,
)

sealed interface PackCorrectionResult {
    data class Superseding(
        val spec: SupersedingSpec,
    ) : PackCorrectionResult

    /** A refusal returns no spec. */
    sealed interface Refused : PackCorrectionResult {
        /** The requested date is not the bad pack's `effective_from` (the clock date and a later date are refused). */
        data class DateMismatch(
            val requested: LocalDate,
            val badEffectiveFrom: LocalDate,
        ) : Refused

        data class LabelTooLong(
            val label: String,
        ) : Refused

        data object NoEarlierPack : Refused

        data class NotArmed(
            val status: PackStatus,
        ) : Refused
    }
}

/**
 * The superseding pack of a bad armed pack (INV-REQ-962, INV-REQ-1002). Pure: no clock, no port; the date comes only
 * from the bad pack, and the requested date is checked against it.
 */
object PackCorrection {
    const val SUFFIX = "-rollback"
    const val MAX_LABEL = 16

    fun supersede(
        bad: BadPack,
        earlier: EarlierPack?,
        requested: LocalDate,
    ): PackCorrectionResult {
        val label = bad.version + SUFFIX
        return when {
            bad.status != PackStatus.ARMED -> {
                PackCorrectionResult.Refused.NotArmed(bad.status)
            }

            requested != bad.effectiveFrom -> {
                PackCorrectionResult.Refused.DateMismatch(requested, bad.effectiveFrom)
            }

            earlier == null -> {
                PackCorrectionResult.Refused.NoEarlierPack
            }

            label.length > MAX_LABEL -> {
                PackCorrectionResult.Refused.LabelTooLong(label)
            }

            else -> {
                PackCorrectionResult.Superseding(
                    SupersedingSpec(bad.packType, label, bad.effectiveFrom, earlier.contentRef),
                )
            }
        }
    }
}
