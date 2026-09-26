package com.dynokore.platform.rulepackkernel

/**
 * The DDL for a pack holder's own persisted [PackRegistry] table (INV-REQ-1195:
 * "each pack holder … holds it locally, in its own schema or memory"; T22's
 * `new_table` change), generated instead of hand-written so the shape stays
 * identical across `rules-svc` and every pack-holder service embedding this
 * kernel — one migration statement is the single source of truth for the
 * table every holder needs, the same reasoning `TenantRlsMigration` applies to
 * the RLS trailer of a service's own tables.
 *
 * Unlike `TenantRlsMigration`, this DDL owns the whole `CREATE TABLE`, not
 * just a trailer: a service's own table is never the same shape twice, but a
 * rule-pack registry is — every pack holder stores exactly the same columns
 * (STK-13's artefact members plus the identity and effective-dating fields of
 * [PackManifest]).
 *
 * `tenant_id` is nullable, not `NOT NULL` (CS-19's mandatory-`tenant_id` rule
 * is for tenant-scoped data): every pack type this module resolves today is
 * platform-wide (packs.yaml `pack_types[*].tenant_overridable: 'No'`), so a
 * row's `tenant_id` is null in current use. It stays in the shape because
 * [PackRegistry.resolve] is generic over an optional tenant (INV-REQ-770), so
 * a future tenant-scoped pack type needs no migration change — only rows with
 * a `tenant_id` set. No row-level security is enabled: a NULL `tenant_id`
 * would otherwise be invisible under CS-19's usual RLS predicate (`tenant_id
 * = current_setting(...)`, never true against NULL), which would hide every
 * platform-wide pack from every tenant — table-level access control belongs
 * to the embedding service's own role grants, not RLS, for this fully
 * immutable, insert-only table.
 *
 * Rows are immutable (INV-ADR-263, INV-REQ-1089): a service embedding this
 * table grants its runtime role `INSERT`, `SELECT` only, never `UPDATE` or
 * `DELETE` — enforced by that service's own grants, not by this DDL, the same
 * division `TenantRlsMigration` draws between "this helper only ever emits
 * its own half" and the service's own migration for the rest.
 */
object PackRegistryMigration {
    private val IDENTIFIER = Regex("^[a-z_][a-z0-9_]*$")

    /**
     * `CREATE TABLE "schema"."table"` plus its uniqueness constraint and
     * resolution index (STK-13, INV-REQ-770). Appended into a service's own
     * Flyway migration (CS-19 naming, forward-only).
     *
     * @throws IllegalArgumentException if [schema] or [table] is not a plain
     *   lower-case SQL identifier — spliced directly into DDL text.
     */
    fun ddl(
        schema: String,
        table: String,
    ): String {
        require(IDENTIFIER.matches(schema)) { "schema must be a plain lower-case identifier, was '$schema'" }
        require(IDENTIFIER.matches(table)) { "table must be a plain lower-case identifier, was '$table'" }
        val qualified = "$schema.$table"
        val index = "${table}_resolution_idx"
        val uniqueConstraint = "${table}_lineage_key"
        return """
            |CREATE TABLE $qualified (
            |    id                 uuid PRIMARY KEY,
            |    pack_id            text NOT NULL,
            |    pack_type          text NOT NULL,
            |    version            text NOT NULL,
            |    tenant_id          uuid NULL,
            |    effective_from     date NOT NULL,
            |    effective_to       date NULL,
            |    min_engine_version text NOT NULL,
            |    manifest           jsonb NOT NULL,
            |    checksums          jsonb NOT NULL,
            |    signature          bytea NOT NULL,
            |    contents           jsonb NOT NULL,
            |    trusted_anchor     text NOT NULL,
            |    created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
            |    CONSTRAINT $uniqueConstraint UNIQUE (pack_type, tenant_id, version)
            |);
            |CREATE INDEX $index ON $qualified (pack_type, tenant_id, effective_from);
        """.trimMargin()
    }
}
