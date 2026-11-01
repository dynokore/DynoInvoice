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

    fun isObserveOn(date: LocalDate): Boolean = modeOn(date)?.equals(OBSERVE, ignoreCase = true) == true

    companion object {
        const val OBSERVE = "OBSERVE"

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
            val newer = stored == null || node.structureVersion > stored.structureVersion
            return if (newer) IssuanceReplicaRow(tenant, node.issuanceModes, node.structureVersion) else stored
        }
    }
}

/** Why a clearing or a removal is refused (SUB-023 for clearing, SUB-019 for removal). */
data class ObserveRefusal(
    val connection: String,
    val entities: List<EntityRef>,
) {
    val message: String
        get() = "connection $connection: entities in Observe mode or with no known mode: ${entities.joinToString()}"
}

/** The Observe guard (INV-REQ-1310, fail closed): an entity with no replica row is treated as possibly Observe. */
object ObserveGuard {
    fun refusal(
        connection: String,
        entities: List<EntityRef>,
        rows: (EntityRef) -> IssuanceReplicaRow?,
        today: LocalDate,
    ): ObserveRefusal? {
        val blocking = entities.filter { e -> rows(e)?.isObserveOn(today) ?: true }
        return if (blocking.isEmpty()) null else ObserveRefusal(connection, blocking)
    }
}
