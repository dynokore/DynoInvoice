package com.dynokore.platform.rulesevaluation.coding

/**
 * The mapping entries of the Mapping layer, as the line coder takes them (INV-REQ-363, INV-REQ-364): one entry per
 * internal tax code. There is no default entry: an internal code with no entry has no answer here ([entryOf] is
 * null), and the line coder refuses it (RUL-001). The code is looked up by exact string equality, never normalised.
 */
class TenantMapping(
    entries: List<MappingEntry>,
) {
    val entries: List<MappingEntry> = entries.toList()
    private val byInternalCode: Map<String, MappingEntry> =
        this.entries.groupBy { it.internalTaxCode }.mapValues { (code, rows) ->
            require(rows.size == 1) { "internal tax code $code is mapped twice" }
            rows.single()
        }

    fun entryOf(internalTaxCode: String): MappingEntry? = byInternalCode[internalTaxCode]
}
