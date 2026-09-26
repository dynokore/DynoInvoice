package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * The append-only ledger's mandatory migration trailer (CS-19, CS-22;
 * done-when: "in-place update refused by DB grants and trigger"), generated
 * instead of hand-written per evidence table the same way
 * [TenantRlsMigration] is (whose RLS DDL this reuses directly — CODER-BRIEF:
 * "reuse tenancy-kernel's RLS helpers" — rather than a second copy of it). A
 * service's own Flyway migration still declares the table itself (`id`,
 * `tenant_id`, `entity_ref`, `period_ref`, `seq`, `content_hash`,
 * `previous_hash`, `recorded_at`, and its own evidence-type-specific columns,
 * TPL-12), then appends this.
 *
 * Two independent controls enforce immutability, not one (CS-22's own
 * enforcement column: "DB grants" *and*, here, a trigger too):
 *  1. `REVOKE UPDATE, DELETE, TRUNCATE` from the application role — the normal
 *     path is refused with a permission error before a statement even runs.
 *  2. A `BEFORE UPDATE OR DELETE` trigger that raises unconditionally,
 *     regardless of role — including the owner role that runs migrations and
 *     so still holds those grants (the same "the owner is also bound"
 *     reasoning `FORCE ROW LEVEL SECURITY` gives for tenancy, TPL-12, applied
 *     here to mutability instead).
 */
object EvidenceLedgerSchema {
    private val IDENTIFIER = Regex("^[a-z_][a-z0-9_]*$")

    /**
     * The `ENABLE`/`FORCE ROW LEVEL SECURITY` and tenant-isolation policy DDL
     * (reused from [TenantRlsMigration]), plus the `REVOKE` and the
     * append-only trigger, for `"schema"."table"`.
     *
     * @throws IllegalArgumentException if [schema], [table] or [appRole] is
     *   not a plain lower-case SQL identifier — this text is spliced directly
     *   into DDL, so it is validated the same way a bind parameter never
     *   needs to be.
     */
    fun trailerDdl(
        schema: String,
        table: String,
        appRole: String,
        policyName: String = "tenant_isolation",
    ): String {
        require(IDENTIFIER.matches(schema)) { "schema must be a plain lower-case identifier, was '$schema'" }
        require(IDENTIFIER.matches(table)) { "table must be a plain lower-case identifier, was '$table'" }
        require(IDENTIFIER.matches(appRole)) { "appRole must be a plain lower-case identifier, was '$appRole'" }
        val qualified = "$schema.$table"
        val guardFunction = "${table}_immutable"
        val rls = TenantRlsMigration.ddl(schema, table, policyName)
        return """
            |$rls
            |REVOKE UPDATE, DELETE, TRUNCATE ON $qualified FROM $appRole;
            |CREATE OR REPLACE FUNCTION $schema.$guardFunction() RETURNS trigger AS $$
            |BEGIN
            |    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
            |END;
            |$$ LANGUAGE plpgsql;
            |CREATE TRIGGER ${table}_no_mutation
            |    BEFORE UPDATE OR DELETE ON $qualified
            |    FOR EACH ROW EXECUTE FUNCTION $schema.$guardFunction();
        """.trimMargin()
    }
}
