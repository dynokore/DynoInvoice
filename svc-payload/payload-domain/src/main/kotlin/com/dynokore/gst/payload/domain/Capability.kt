package com.dynokore.gst.payload.domain

/** A capability of payload-svc that is not considered specified until its precondition holds (INV-REQ-481). */
enum class CapabilityKey {
    SERIALISATION,
}

/** The state of a capability in the registry; the name of `markSpecified` fixes the second value. */
enum class CapabilityState {
    NOT_SPECIFIED,
    SPECIFIED,
}

/**
 * Where the MDE binding table of INV-REQ-480 lives and which columns carry the three values a complete row must
 * hold: the Business Term ID, the PINT-SG cardinality and the IRAS MDE status. The names are exact; a look-alike
 * name is another table.
 */
data class BindingTableRef(
    val schema: String,
    val table: String,
    val businessTermIdColumn: String,
    val pintSgCardinalityColumn: String,
    val irasMdeStatusColumn: String,
) {
    init {
        require(
            listOf(
                schema,
                table,
                businessTermIdColumn,
                pintSgCardinalityColumn,
                irasMdeStatusColumn,
            ).all { it.isNotBlank() },
        ) { "a binding table reference needs a non-blank schema, table and three column names" }
    }

    val qualifiedName: String get() = "$schema.$table"
}
