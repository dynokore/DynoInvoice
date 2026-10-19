package com.dynokore.platform.serviceruntime.persistence

import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.jooq.Table

/**
 * The tables [AggregateStore] refuses at construction (INV-T-4224; INV-ADR-3968 item 11: no runtime login holds UPDATE
 * or DELETE on the audit entries, the override grants or the evidence ledgers).
 *
 * @property protectedTables table names refused in any schema: `audit_entry`, `override_grant` and the evidence
 * ledger's
 *   table (`service-runtime.modules.evidence.ledger-table`). Exact equality.
 * @property appendOnlyTables `schema.table` names the service declares append-only
 * (`inv.persistence.append-only-tables`).
 *   Exact equality.
 */
class PersistenceGuards(
    protectedTables: Collection<String>,
    appendOnlyTables: Collection<String>,
) {
    private val protectedNames = protectedTables.toSet()
    private val appendOnly = requireAppendOnlyNames(appendOnlyTables).toSet()

    /** Why an [AggregateStore] cannot be made for [table], or null. */
    fun storeRefusal(table: Table<*>): String? {
        val qualified = table.qualifiedName.name.joinToString(".")
        return when {
            table.name in protectedNames -> {
                "table $qualified is protected (INV-ADR-3968 item 11: audit entries, override grants and evidence " +
                    "ledgers are never updated or deleted by a runtime login)"
            }

            qualified in appendOnly -> {
                "table $qualified is declared append-only: use LedgerAppender"
            }

            else -> {
                null
            }
        }
    }

    companion object {
        /**
         * Returns [entries] when each is two non-blank lower-case parts joined by one dot, each a plain identifier
         * ([SqlIdentifier.require]), and none repeats another; otherwise throws [IllegalArgumentException] naming the
         * entry. A well-formed entry that matches no table is not an error: it does nothing.
         */
        fun requireAppendOnlyNames(entries: Collection<String>): Collection<String> {
            val seen = mutableSetOf<String>()
            entries.forEach { entry ->
                val where = "inv.persistence.append-only-tables entry '$entry'"
                require(entry.isNotBlank()) { "$where is blank" }
                val parts = entry.split('.')
                require(parts.size == 2) { "$where must be schema.table, two parts joined by one dot" }
                SqlIdentifier.require(parts[0], "$where: schema")
                SqlIdentifier.require(parts[1], "$where: table")
                require(seen.add(entry)) { "$where is repeated" }
            }
            return entries
        }
    }
}
