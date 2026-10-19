package com.dynokore.platform.tenancykernel

/**
 * The mandatory RLS trailer for a tenant-scoped table (CS-19), generated
 * instead of hand-written in every service's own migration, so the
 * error-prone part — `ENABLE`/`FORCE ROW LEVEL SECURITY` plus the exact policy
 * predicate — cannot be forgotten, mistyped, or left off `FORCE` (which alone
 * would leave the owning role itself unbound by its own policy).
 *
 * The policy predicate is fixed platform-wide:
 * `tenant_id = (select <schema>.bound_tenant())` (INV-REQ-854 b7, owner ruling
 * INV-Q-4121; INV-T-4156) — the function verifies the signed `app.binding_sig`
 * and returns `NULL` for a missing, forged or expired value, so a connection with
 * nothing bound (a leaked or missing [TransactionRunner] context), or one whose
 * `app.tenant_id` was set by hand, matches no row, ever (fail-closed, CS-19
 * "V-10"). The sub-select makes it an InitPlan, evaluated once per statement.
 * `<schema>.bound_tenant()` ([BindingKeyMigration.ddl]) must exist before the
 * policy is created.
 *
 * A service's own Flyway migration still declares the table itself
 * (`tenant_id uuid NOT NULL` — no default, TPL-12) and its own grants; this
 * helper only ever emits the RLS half, since the table shape is never the
 * same twice. [ddl] is deterministic pure text generation — no database
 * connection, no framework — so it belongs in this module's `-impl` alongside
 * the other adapters-layer concerns a service's own migration resources pull
 * in (TPL-01: `<svc>-adapters` depends on `tenancy-kernel` impl, and
 * `db/migration` lives in `<svc>-adapters`).
 */
object TenantRlsMigration {
    /**
     * The `ENABLE`/`FORCE ROW LEVEL SECURITY` and `CREATE POLICY` statements
     * for `"schema"."table"` (CS-19). Appended after a service's own
     * `CREATE TABLE` in the same migration file (TPL-12).
     *
     * @throws IllegalArgumentException if [schema], [table] or [policyName] is
     *   not a plain lower-case SQL identifier of at most 63 characters — this
     *   text is spliced directly into DDL, so each name goes through
     *   [SqlIdentifier.require] (there is no bind parameter for an identifier;
     *   INV-REQ-854 b5, coordinator ruling INV-Q-4036).
     */
    fun ddl(
        schema: String,
        table: String,
        policyName: String = "tenant_isolation",
    ): String {
        val schemaName = SqlIdentifier.require(schema, "schema")
        val tableName = SqlIdentifier.require(table, "table")
        val policy = SqlIdentifier.require(policyName, "policyName")
        return """
            |ALTER TABLE $schemaName.$tableName ENABLE ROW LEVEL SECURITY;
            |ALTER TABLE $schemaName.$tableName FORCE ROW LEVEL SECURITY;
            |CREATE POLICY $policy ON $schemaName.$tableName
            |    USING      (tenant_id = (select $schemaName.bound_tenant()))
            |    WITH CHECK (tenant_id = (select $schemaName.bound_tenant()));
            """.trimMargin()
    }
}
