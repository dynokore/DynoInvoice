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

    /** The three grants the holder copy carries: (grantee, privilege, grant option). */
    val EXPECTED_GRANTS =
        setOf(
            Triple(RUNTIME, "INSERT", false),
            Triple(RUNTIME, "SELECT", false),
            Triple(SYS, "INSERT", false),
        )

    /**
     * The grants on the holder copy (INV-Q-8204 A): the set of (grantee, privilege, grant option) triples whose grantee
     * is not the owner equals [EXPECTED_GRANTS] by set equality (the sys triple is the kernel DDL's grant to the owner
     * of its definer function), and the column-level ACL is empty. A grant to `PUBLIC` or to any other role, any other
     * privilege and any grant option is a finding, as is a missing expected triple. Whether the sys role can log in is
     * checked separately ([loginFindings]).
     */
    fun grantFindings(grants: List<Grant>): List<String> {
        val findings = mutableListOf<String>()
        val others = grants.filter { it.grantee != OWNER }
        others.filter { it.column != null }.forEach {
            findings += "${it.grantee} holds ${it.privilege} on column ${it.column}"
        }
        val triples = others.filter { it.column == null }.map { Triple(it.grantee, it.privilege, it.grantable) }
        (triples.toSet() - EXPECTED_GRANTS).forEach { findings += "unexpected grant $it" }
        (EXPECTED_GRANTS - triples.toSet()).forEach { findings += "missing grant $it" }
        if (triples.size != triples.toSet().size) findings += "a grant is listed twice"
        return findings
    }

    /** `rules_sys` is NOLOGIN: [canLogin] is `pg_roles.rolcanlogin` of that role, null when the role does not exist. */
    fun loginFindings(canLogin: Boolean?): List<String> =
        when (canLogin) {
            false -> emptyList()
            true -> listOf("$SYS can log in")
            null -> listOf("$SYS does not exist")
        }
}
