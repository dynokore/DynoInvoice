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
 * is for a table whose rows are *always* tenant-scoped): this table holds
 * both — most pack types are platform-wide (packs.yaml
 * `pack_types[*].tenant_overridable: 'No'`, e.g. Scope, Code catalogue, MDE),
 * but the `Mapping` pack type is `tenant_overridable: Yes` ("this is the
 * tenant's own pack"), so a real deployment holds a mix of `tenant_id IS NULL`
 * rows (platform-wide) and `tenant_id = <tenant>` rows (a tenant's own
 * Mapping pack) side by side. [PackRegistry.resolve] is generic over an
 * optional tenant (INV-REQ-770) for exactly this mix.
 *
 * Row-level security is still mandatory here (CS-19), just not CS-19's usual
 * single-tenant predicate: that predicate (`tenant_id =
 * current_setting(...)`) is never true against a NULL `tenant_id`, so it
 * would hide every platform-wide pack from every tenant. Instead the policy
 * predicate also admits a NULL `tenant_id` — platform-wide rows stay visible
 * to every tenant, while a tenant-scoped row (`tenant_id` set) is still
 * fail-closed exactly as CS-19 requires: invisible whenever the session has
 * no tenant bound (an unset or blank `app.tenant_id`, e.g. a leaked or
 * missing [TransactionRunner] context). [TenantRlsMigration] is not reused
 * here because it hard-codes both the strict single-tenant predicate and a
 * `tenant_id NOT NULL` table shape, neither of which fits a table that must
 * also carry platform-wide rows.
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
     * `CREATE TABLE "schema"."table"` plus its uniqueness constraint,
     * resolution index, and the row-level-security trailer that admits both
     * platform-wide (`tenant_id IS NULL`) and tenant-scoped rows (STK-13,
     * INV-REQ-770, CS-19). Appended into a service's own Flyway migration
     * (CS-19 naming, forward-only).
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
        val policyName = "${table}_tenant_isolation"
        // CS-19's predicate, but also admitting a NULL tenant_id (platform-wide
        // rows): unlike TenantRlsMigration's single-tenant predicate, this table's
        // rows are not all tenant-scoped (see class doc). A tenant-scoped row
        // still fails closed: `nullif(current_setting(...), '')` is NULL when the
        // setting is absent or blank, and `tenant_id = NULL` is never true.
        val predicate = "tenant_id IS NULL OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid"
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
            |ALTER TABLE $qualified ENABLE ROW LEVEL SECURITY;
            |ALTER TABLE $qualified FORCE ROW LEVEL SECURITY;
            |CREATE POLICY $policyName ON $qualified
            |    USING      ($predicate)
            |    WITH CHECK ($predicate);
        """.trimMargin()
    }
}
