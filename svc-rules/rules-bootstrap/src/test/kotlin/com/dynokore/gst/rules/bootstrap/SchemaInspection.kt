package com.dynokore.gst.rules.bootstrap

/**
 * The inspection of INV-AC-2182 over what the PostgreSQL catalog says about schema `rules` (INV-T-4965, INV-T-7525).
 * Pure functions over rows read from `pg_catalog`, so the probes (look-alike tables, a document column in a metadata
 * table, a grant to `PUBLIC`) run without a database and the integration test feeds them the real catalog.
 * Every comparison is exact equality of the schema and table name; nothing is matched by prefix or pattern.
 */
internal object SchemaInspection {
    data class Table(
        val schema: String,
        val name: String,
    )

    data class Column(
        val table: Table,
        val name: String,
        val type: String,
    )

    /** One privilege on a table (`column` null) or on one of its columns, as `aclexplode` returns it. */
    data class Grant(
        val grantee: String,
        val privilege: String,
        val grantable: Boolean = false,
        val column: String? = null,
    )

    const val SCHEMA = "rules"
    const val HOLDER = "pack_artefact"
    const val OWNER = "rules_migrate"
    const val RUNTIME = "rules_runtime"
    const val SYS = "rules_sys"

    /** The metadata tables of V1: registry and activation state only, no document-shaped column. */
    val REGISTRY_TABLES = setOf("pack_registry", "pack_entry_source")

    /** The columns of the kernel's registry table (`PackRegistryMigration.ddl`), `pack_version` and not `version`. */
    val HOLDER_COLUMNS =
        setOf(
            "id",
            "pack_id",
            "pack_type",
            "pack_version",
            "tenant_id",
            "effective_from",
            "effective_to",
            "min_engine_version",
            "manifest",
            "checksums",
            "signature",
            "contents",
            "trusted_anchor",
            "registered_by",
            "created_at",
        )

    val DOCUMENT_TYPES = setOf("json", "jsonb", "bytea")

    /** The privileges the runtime login holds on the holder copy: it reads and inserts, rows are immutable. */
    val RUNTIME_PRIVILEGES = setOf("INSERT", "SELECT")

    private val NEVER = setOf("UPDATE", "DELETE", "TRUNCATE")

    /**
     * Deny by default: every table of the schema is the holder copy, a registry table or a table [libraries] names
     * (platform libraries and Flyway); anything else is a finding, as is a missing or mis-shaped holder copy.
     */
    fun tableFindings(
        tables: List<Table>,
        columns: List<Column>,
        libraries: Set<String>,
    ): List<String> {
        val findings = mutableListOf<String>()
        val holder = Table(SCHEMA, HOLDER)
        tables
            .filter { it.schema == SCHEMA && it.name != HOLDER && it.name !in REGISTRY_TABLES && it.name !in libraries }
            .forEach { findings += "table ${it.schema}.${it.name} is not classified" }
        if (holder !in tables) {
            findings += "table $SCHEMA.$HOLDER does not exist"
        } else {
            val names = columns.filter { it.table == holder }.map { it.name }
            if (names.toSet() != HOLDER_COLUMNS || names.size != HOLDER_COLUMNS.size) {
                findings += "columns of $SCHEMA.$HOLDER are $names, expected $HOLDER_COLUMNS"
            }
        }
        // json, jsonb and bytea are the holder copy's alone: no registry table holds a document-shaped column
        columns
            .filter { it.table.schema == SCHEMA && it.table.name in REGISTRY_TABLES && it.type in DOCUMENT_TYPES }
            .forEach { findings += "${it.table.schema}.${it.table.name}.${it.name} has the document type ${it.type}" }
        return findings
    }

    /**
     * The grants on the holder copy. Set equality for the runtime login (INSERT, SELECT), no UPDATE, DELETE or TRUNCATE
     * for anyone, no grant option, nothing to `PUBLIC` or to an unnamed role. [allowedOthers] are the roles other than
     * the runtime login that the kernel DDL itself grants (see the gap in the merge request), each with its
     * privileges.
     */
    fun grantFindings(
        grants: List<Grant>,
        allowedOthers: Map<String, Set<String>>,
    ): List<String> {
        val findings = mutableListOf<String>()
        val others = grants.filter { it.grantee != OWNER }
        others.filter { it.privilege in NEVER }.forEach { findings += "${it.grantee} holds ${it.privilege}" }
        others.filter { it.grantable }.forEach { findings += "${it.grantee} holds ${it.privilege} WITH GRANT OPTION" }
        others.filter { it.column != null }.forEach {
            findings += "${it.grantee} holds ${it.privilege} on column ${it.column}"
        }
        val byGrantee = others.groupBy({ it.grantee }, { it.privilege })
        byGrantee.keys
            .filter { it != RUNTIME && it !in allowedOthers }
            .forEach { findings += "$it holds a grant on $SCHEMA.$HOLDER" }
        val runtime = byGrantee[RUNTIME].orEmpty()
        if (runtime.toSet() != RUNTIME_PRIVILEGES || runtime.size != RUNTIME_PRIVILEGES.size) {
            findings += "$RUNTIME holds $runtime, expected $RUNTIME_PRIVILEGES"
        }
        allowedOthers.forEach { (role, privileges) ->
            val held = byGrantee[role].orEmpty()
            if (held.toSet() != privileges || held.size != privileges.size) {
                findings += "$role holds $held, expected $privileges"
            }
        }
        return findings
    }
}
