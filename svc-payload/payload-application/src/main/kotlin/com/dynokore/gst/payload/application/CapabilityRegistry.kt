package com.dynokore.gst.payload.application

import com.dynokore.gst.payload.domain.BindingTableRef
import com.dynokore.gst.payload.domain.CapabilityKey
import com.dynokore.gst.payload.domain.CapabilityState

/** The capability registry of schema `payload` (INV-REQ-481). */
interface CapabilityRegistry {
    fun state(key: CapabilityKey): CapabilityState

    /** Sets [key] to SPECIFIED; a no-op for a capability that is already SPECIFIED. */
    fun markSpecified(key: CapabilityKey)
}

/** What the database holds of the MDE binding table of INV-REQ-480. */
interface BindingTableInspector {
    fun exists(table: BindingTableRef): Boolean

    /** Rows of [table] that lack a Business Term ID, a PINT-SG cardinality or an IRAS MDE status (null or blank). */
    fun incompleteRows(table: BindingTableRef): Int

    fun rows(table: BindingTableRef): Int
}

/**
 * `markSpecified(SERIALISATION)` (INV-REQ-481, INV-AC-7842, INV-AC-7843): refused with an [IllegalStateException]
 * while the binding table is absent, empty or holds an incomplete row; the state then stays as it was.
 */
class CapabilityService(
    private val registry: CapabilityRegistry,
    private val inspector: BindingTableInspector,
    private val bindingTable: BindingTableRef,
) {
    fun markSpecified(key: CapabilityKey) {
        when (key) {
            CapabilityKey.SERIALISATION -> requireCompleteBindingTable()
        }
        registry.markSpecified(key)
    }

    private fun requireCompleteBindingTable() {
        check(inspector.exists(bindingTable)) {
            "the MDE binding table ${bindingTable.qualifiedName} does not exist: serialisation is not specified"
        }
        check(inspector.rows(bindingTable) > 0) {
            "the MDE binding table ${bindingTable.qualifiedName} holds no row: serialisation is not specified"
        }
        val incomplete = inspector.incompleteRows(bindingTable)
        check(incomplete == 0) {
            "the MDE binding table ${bindingTable.qualifiedName} holds $incomplete row(s) without a Business Term " +
                "ID, a PINT-SG cardinality or an IRAS MDE status: serialisation is not specified"
        }
    }
}
