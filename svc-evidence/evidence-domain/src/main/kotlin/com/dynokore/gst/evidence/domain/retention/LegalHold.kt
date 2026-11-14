package com.dynokore.gst.evidence.domain.retention

import java.time.LocalDate

/** The state of a legal hold; only an ACTIVE hold exempts a period from deletion. */
enum class HoldStatus { ACTIVE, RELEASED }

/** A legal hold placed on one entity and one period (INV-REQ-639). */
data class LegalHold(
    val holdId: String,
    val entityRef: String,
    val periodRef: LocalDate,
    val reason: String,
    val status: HoldStatus,
) {
    init {
        require(holdId.isNotBlank()) { "holdId must not be blank" }
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
    }

    /** True when this hold is ACTIVE, is on [entityRef] (exact equality) and its period is on [periodRef]. */
    fun covers(
        entityRef: String,
        periodRef: LocalDate,
    ): Boolean = status == HoldStatus.ACTIVE && this.entityRef == entityRef && this.periodRef == periodRef

    /** True when this hold is ACTIVE, is on [entityRef] and its period lies in [from]..[to] inclusive. */
    fun coversPartition(
        entityRef: String,
        from: LocalDate,
        to: LocalDate,
    ): Boolean =
        status == HoldStatus.ACTIVE && this.entityRef == entityRef && !periodRef.isBefore(from) &&
            !periodRef.isAfter(to)
}
