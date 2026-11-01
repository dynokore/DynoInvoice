package com.dynokore.gst.transport.domain

import java.time.LocalDate

/** One `issuanceMode` entry of `node.profile`: the value (opaque text) and the date it takes effect. */
data class IssuanceModeEntry(
    val value: String,
    val effectiveDate: LocalDate,
)

/** The `node` of an EntityProfileChanged event, reduced to what this service reads; null profile means no entries. */
data class EntityNode(
    val structureVersion: Long,
    val issuanceModes: List<IssuanceModeEntry>,
)

/** The replica row of an entity: its tenant, its issuanceMode entries and the structure version, nothing else. */
data class IssuanceReplicaRow(
    val tenant: TenantRef,
    val entries: List<IssuanceModeEntry>,
    val structureVersion: Long,
) {
    /** The entry with the latest effective date on or before [date]; none when every entry is still in the future. */
    fun modeOn(date: LocalDate): String? =
        entries
            .filter { !it.effectiveDate.isAfter(date) }
            .maxByOrNull { it.effectiveDate }
            ?.value

    /** Observe, or a value in force that is not a known mode (fail closed); no entry in force is not Observe. */
    fun mayBeObserveOn(date: LocalDate): Boolean = modeOn(date)?.let { it == OBSERVE || it !in KNOWN_MODES } ?: false

    fun isObserveOn(date: LocalDate): Boolean = modeOn(date) == OBSERVE

    /** True when the mode in force on [date] is a value outside [KNOWN_MODES]. */
    fun unknownModeOn(date: LocalDate): Boolean = modeOn(date)?.let { it !in KNOWN_MODES } ?: false

    companion object {
        const val OBSERVE = "OBSERVE"

        /** The names of the tenancy IssuanceMode, published as `mode.name`. */
        val KNOWN_MODES: Set<String> = setOf("RELAY", OBSERVE, "NONE")

        /**
         * The row after an event: replaced whole when the node carries an issuanceMode entry and a structureVersion
         * above the stored one; otherwise the stored row (possibly none) is returned unchanged.
         */
        fun apply(
            stored: IssuanceReplicaRow?,
            tenant: TenantRef,
            node: EntityNode?,
        ): IssuanceReplicaRow? {
            if (node == null || node.issuanceModes.isEmpty()) return stored
            val newer = stored == null || (stored.tenant == tenant && node.structureVersion > stored.structureVersion)
            return if (newer) IssuanceReplicaRow(tenant, node.issuanceModes, node.structureVersion) else stored
        }
    }
}

/** Why a clearing or a removal is refused (SUB-023 for clearing, SUB-019 for removal). */
data class ObserveRefusal(
    val connection: String,
    val entities: List<EntityRef>,
    val unknownMode: Boolean = false,
) {
    val message: String
        get() =
            "connection $connection: entities in Observe mode, with no replica row or with no known mode" +
                (if (unknownMode) " (a mode in force is not RELAY, OBSERVE or NONE)" else "") +
                ": ${entities.joinToString()}"
}

/** The Observe guard (INV-REQ-1310, fail closed): no row, a row of another tenant or an unknown mode may be Observe. */
object ObserveGuard {
    fun refusal(
        connection: String,
        tenant: TenantRef,
        entities: List<EntityRef>,
        rows: (EntityRef) -> IssuanceReplicaRow?,
        today: LocalDate,
    ): ObserveRefusal? {
        val owned = entities.associateWith { e -> rows(e)?.takeIf { it.tenant == tenant } }
        val blocking = owned.filter { (_, row) -> row == null || row.mayBeObserveOn(today) }.keys.toList()
        if (blocking.isEmpty()) return null
        return ObserveRefusal(connection, blocking, blocking.any { owned.getValue(it)?.unknownModeOn(today) == true })
    }
}
