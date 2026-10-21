package com.dynokore.gst.payload.application

import com.dynokore.gst.payload.domain.CapabilityKey
import com.dynokore.gst.payload.domain.CapabilityState

/** The capability registry of schema `payload` (INV-REQ-481). */
interface CapabilityRegistry {
    fun state(key: CapabilityKey): CapabilityState

    /**
     * Sets [key] to SPECIFIED. The database accepts the write only in a transaction bound to the platform tenant
     * (INV-REQ-854 behavior 2); a no-op for a capability that is already SPECIFIED.
     */
    fun markSpecified(key: CapabilityKey)
}

/**
 * The facts of one binding row (INV-REQ-480) that the guard checks: the Business Term ID, the IRAS MDE status and one
 * PINT-SG cardinality per entry of the row's `specifications` array. The names differ on purpose from the Layer 2
 * types of INV-T-6790.
 */
data class BindingRowFacts(
    val businessTermId: String?,
    val irasStatus: String?,
    val pintCardinalities: List<String?>,
)

/** The active MDE pack (packType `mde`) as the guard sees it: its version and its binding rows. */
data class ActiveMdePack(
    val version: String,
    val rows: List<BindingRowFacts>,
)

/** Where the contents of the active MDE pack come from; the binding table of INV-REQ-480 is these contents. */
interface MdePackSource {
    /** The active MDE pack, or null while none is active. */
    fun active(): ActiveMdePack?
}

/** The source of the production wiring until the task that loads the pack replaces it: no pack is active. */
class AbsentMdePackSource : MdePackSource {
    override fun active(): ActiveMdePack? = null
}

/**
 * `markSpecified(SERIALISATION)` (INV-REQ-481, INV-AC-7842, INV-AC-7843): refused with an [IllegalStateException]
 * naming the MDE pack while no pack is active, its rows are empty or a row is incomplete; the state then stays as it
 * was. The status value itself is not judged here: the pack schema fixes it and Layer 2 applies it.
 */
class CapabilityService(
    private val registry: CapabilityRegistry,
    private val packs: MdePackSource,
) {
    fun markSpecified(key: CapabilityKey) {
        when (key) {
            CapabilityKey.SERIALISATION -> requireCompletePack(packs.active())
        }
        registry.markSpecified(key)
    }

    private fun requireCompletePack(pack: ActiveMdePack?) {
        checkNotNull(pack) { "$PACK: no pack is active, serialisation is not specified" }
        check(pack.rows.isNotEmpty()) { "$PACK: the active pack holds no row, serialisation is not specified" }
        pack.rows.forEachIndexed { index, row ->
            check(!row.businessTermId.isNullOrBlank()) { "$PACK: row $index has no Business Term ID" }
            check(!row.irasStatus.isNullOrBlank()) { "$PACK: row $index has no IRAS MDE status" }
            check(row.pintCardinalities.isNotEmpty()) { "$PACK: row $index has no specification entry" }
            row.pintCardinalities.forEachIndexed { entry, cardinality ->
                check(!cardinality.isNullOrBlank()) { "$PACK: row $index entry $entry has no PINT-SG cardinality" }
            }
        }
    }

    private companion object {
        const val PACK = "MDE pack (packType mde)"
    }
}
