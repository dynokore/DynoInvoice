package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.SqlIdentifier

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
 * optional tenant (INV-REQ-770) for exactly this mix. Keel INV-REQ-1343
 * names who owns the platform-wide half: "the platform operator maintains
 * the regulatory rule pack layer" (pack authoring, signing and release
 * management "sit in the control plane, which is always the operator") — the
 * identity below, `app.platform_operator`, is that operator's own write path
 * (`control-plane-svc`/`rules-svc` staging a regulatory pack), distinct from
 * any tenant's own session.
 *
 * Row-level security is still mandatory here (CS-19), just not CS-19's usual
 * single-tenant predicate, and — after a review escalation — not a predicate
 * that treats an absent tenant as an implicit identity either. Two session
 * GUCs decide visibility and writes:
 * - `app.tenant_id`: unset, blank, or a tenant's own UUID (exactly as
 *   `TenantRlsMigration` and [TransactionRunner] already use it elsewhere —
 *   never overloaded with a non-UUID sentinel here, see below).
 * - `app.platform_operator`: `'true'` only on the platform operator's own
 *   write/read path (INV-REQ-1343); unset everywhere else, including every
 *   tenant's own session.
 *
 * `USING` (read): a `tenant_id IS NULL` (platform-wide) row is visible to a
 * session that has *some* identity bound — a real tenant, or the operator —
 * never to a session with neither (CS-19's fail-closed guarantee, applied to
 * platform-wide rows too, not only tenant-scoped ones: a leaked or missing
 * [TransactionRunner] context must see nothing, exactly as
 * `TenancyKernelPostgresIT.assertFailsClosedWithNoTenantBound` already proves
 * for tenancy-kernel's own tables). A tenant-scoped row is visible only to
 * its own tenant, as CS-19's usual predicate already guarantees.
 *
 * `WITH CHECK` (write) is deliberately not the same text as `USING`: a bare
 * "visible to anyone with an identity" would let a tenant-bound session
 * insert a `tenant_id IS NULL` row too (a bug in a tenant-bound write path
 * could fabricate a "platform-wide" row visible to every tenant, with RLS as
 * the sole enforcement — the embedding role is INSERT/SELECT-only, no
 * `BYPASSRLS`). A `tenant_id IS NULL` row is insertable only when
 * `app.platform_operator = 'true'`, never merely because no tenant happens
 * to be bound.
 *
 * `app.platform_operator` is a *second*, separate GUC rather than a
 * non-UUID sentinel value written into `app.tenant_id` (e.g. `'operator'`):
 * `USING` must scan arbitrarily many rows with a fixed session state, and a
 * sentinel sharing `app.tenant_id`'s column would reach
 * `tenant_id = <sentinel>::uuid` while scanning some *other*, tenant-owned
 * row under the operator's session — an invalid-UUID cast error aborting the
 * whole query, not merely a non-match. Keeping `app.tenant_id` always either
 * absent, blank, or a real tenant UUID avoids that failure mode entirely;
 * `WITH CHECK` only ever evaluates the one row being inserted, so this
 * hazard is specific to the read side. [TenantRlsMigration] is not reused
 * for either predicate: it hard-codes the strict single-tenant predicate and
 * a `tenant_id NOT NULL` table shape, neither of which fits a table that
 * must also carry platform-wide rows and a distinct operator identity.
 *
 * Rows are immutable (INV-ADR-263, INV-REQ-1089): a service embedding this
 * table grants its runtime role `INSERT`, `SELECT` only, never `UPDATE` or
 * `DELETE` — enforced by that service's own grants, not by this DDL, the same
 * division `TenantRlsMigration` draws between "this helper only ever emits
 * its own half" and the service's own migration for the rest.
 */
object PackRegistryMigration {
    /**
     * The forward-only migration for a holder whose registry table already exists
     * from the earlier [ddl] (UNIQUE without NULLS NOT DISTINCT, so a platform pack
     * version, `tenant_id IS NULL`, could be stored twice; INV-Q-2964, INV-T-2996):
     * replaces the lineage constraint with `UNIQUE NULLS NOT DISTINCT`. Fails if the
     * table already holds a duplicate platform version, which the holder resolves
     * first. One ALTER TABLE statement, so a failure leaves the old constraint in place. The
     * holder runs it once, inside its own migration tool. Requires PostgreSQL 15 or later.
     */
    fun lineageKeyUpgradeDdl(
        schema: String,
        table: String,
    ): String {
        val schemaName = SqlIdentifier.require(schema, "schema")
        val tableName = SqlIdentifier.require(table, "table")
        val uniqueConstraint = SqlIdentifier.require("${tableName}_lineage_key", "lineage constraint")
        return """
            |ALTER TABLE $schemaName.$tableName DROP CONSTRAINT $uniqueConstraint,
            |    ADD CONSTRAINT $uniqueConstraint UNIQUE NULLS NOT DISTINCT (pack_type, tenant_id, version);
        """.trimMargin()
    }

    /**
     * `CREATE TABLE "schema"."table"` plus its uniqueness constraint,
     * resolution index, and the row-level-security trailer that admits both
     * platform-wide (`tenant_id IS NULL`) and tenant-scoped rows, fail-closed
     * on both read and write for a session with no identity bound at all
     * (STK-13, INV-REQ-770, INV-REQ-1343, CS-19). Appended into a service's
     * own Flyway migration (CS-19 naming, forward-only).
     *
     * @throws IllegalArgumentException if [schema], [table] or a name derived
     *   from [table] (`<table>_resolution_idx`, `<table>_lineage_key`,
     *   `<table>_tenant_isolation`) is not a plain lower-case SQL identifier of
     *   at most 63 characters — spliced directly into DDL text, so every name
     *   goes through [SqlIdentifier.require] (INV-REQ-854 b5, INV-Q-4036).
     */
    fun ddl(
        schema: String,
        table: String,
    ): String {
        val schemaName = SqlIdentifier.require(schema, "schema")
        val tableName = SqlIdentifier.require(table, "table")
        val index = SqlIdentifier.require("${tableName}_resolution_idx", "resolution index")
        val uniqueConstraint = SqlIdentifier.require("${tableName}_lineage_key", "lineage constraint")
        val policyName = SqlIdentifier.require("${tableName}_tenant_isolation", "policy")
        // Always absent, blank, or a real tenant UUID — never a sentinel (see class doc).
        val boundTenant = "nullif(current_setting('app.tenant_id', true), '')"
        // The platform operator's own identity (INV-REQ-1343): a second GUC, not a
        // value squeezed into app.tenant_id, so it can never reach a ::uuid cast.
        val isPlatformOperator = "current_setting('app.platform_operator', true) = 'true'"
        val hasSomeIdentity = "($boundTenant IS NOT NULL OR $isPlatformOperator)"
        // Read: a platform-wide row needs *some* identity bound, tenant or operator
        // (fail-closed otherwise, CS-19); a tenant-scoped row still needs its own.
        val readPredicate = "(tenant_id IS NULL AND $hasSomeIdentity) OR tenant_id = $boundTenant::uuid"
        // Write: a platform-wide row needs the operator identity specifically, never
        // merely "no tenant bound" (the review escalation this fixes) — see class doc.
        val writePredicate = "(tenant_id IS NULL AND $isPlatformOperator) OR tenant_id = $boundTenant::uuid"
        return """
            |CREATE TABLE $schemaName.$tableName (
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
            |    CONSTRAINT $uniqueConstraint UNIQUE NULLS NOT DISTINCT (pack_type, tenant_id, version)
            |);
            |CREATE INDEX $index ON $schemaName.$tableName (pack_type, tenant_id, effective_from);
            |ALTER TABLE $schemaName.$tableName ENABLE ROW LEVEL SECURITY;
            |ALTER TABLE $schemaName.$tableName FORCE ROW LEVEL SECURITY;
            |CREATE POLICY $policyName ON $schemaName.$tableName
            |    USING      ($readPredicate)
            |    WITH CHECK ($writePredicate);
        """.trimMargin()
    }
}
