package com.dynokore.gst.tenancy.application.period

import java.time.Instant
import java.time.LocalDate

/** A staged calendar pack release as RulePackStaged delivers it: the payload is kept whole for the decoder. */
data class StagedCalendarPack(
    val packIdentifier: String,
    val version: String,
    val minEngineVersion: String,
    val payloadJson: String,
)

/** The replica of calendar packs fed by RulePackStaged and RulePackActivated (INV-Q-9725 A); idempotent writes. */
interface CalendarPackReplica {
    /** True when the version was new; a redelivery of a stored version is ignored (false). */
    fun stage(
        pack: StagedCalendarPack,
        recordedAt: Instant,
    ): Boolean

    /** True when the activation was stored; a repeat of a stored identifier and version is false. */
    fun activate(
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
        recordedAt: Instant,
    ): Boolean

    /** The activation dates recorded for a version of a pack identifier (empty when it was never activated). */
    fun activations(
        packIdentifier: String,
        version: String,
    ): List<LocalDate>
}
