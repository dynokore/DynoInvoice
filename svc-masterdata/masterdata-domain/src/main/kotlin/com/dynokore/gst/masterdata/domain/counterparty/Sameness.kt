package com.dynokore.gst.masterdata.domain.counterparty

/** One node of the organisation structure of a tenant as this service holds it (the `node` of the structure events). */
data class StructureNode(
    val nodeRef: String,
    val kind: String,
    val parentRef: String?,
    val retired: Boolean,
    val uen: String?,
    val gstn: String?,
    val structureVersion: Long,
)

/** `sameTenant` and `sameGroup` of a resolution (INV-REQ-720, INV-REQ-738). */
data class Sameness(
    val sameTenant: Boolean,
    val sameGroup: Boolean,
) {
    companion object {
        val NEITHER = Sameness(sameTenant = false, sameGroup = false)
        const val ENTITY = "ENTITY"
        const val GROUP = "GROUP"
    }
}

/**
 * INV-Q-8427 A: `sameTenant` holds exactly when the counterparty's UEN equals the `uen` of a non-retired ENTITY node of
 * the tenant; `sameGroup` holds when, besides, the resolving entity's node and that node have the same parent and that
 * parent is a GROUP node holding a `gstn` (a GST group). [nodes] are the nodes of the resolving entity's tenant. The
 * comparison of the UEN is exact.
 */
fun samenessOf(
    resolvingEntity: String,
    counterpartyUen: String?,
    nodes: List<StructureNode>,
): Sameness {
    val matches =
        if (counterpartyUen == null) {
            emptyList()
        } else {
            nodes.filter { it.kind == Sameness.ENTITY && !it.retired && it.uen == counterpartyUen }
        }
    val parentOfResolving = nodes.firstOrNull { it.nodeRef == resolvingEntity }?.parentRef
    val group =
        parentOfResolving != null &&
            matches.any { it.parentRef == parentOfResolving } &&
            nodes.any { it.nodeRef == parentOfResolving && it.kind == Sameness.GROUP && !it.gstn.isNullOrBlank() }
    return Sameness(sameTenant = matches.isNotEmpty(), sameGroup = group)
}

/** The items of [CounterpartyContent.schemes] in force on a date: the resolution answer (INV-REQ-2445 b3). */
fun CounterpartyContent.schemesInForceOn(date: java.time.LocalDate): List<SchemeStatus> =
    schemes.filter { it.inForceOn(date) }
