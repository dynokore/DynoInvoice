package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.SqlIdentifier
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
    /**
     * The `ENABLE`/`FORCE ROW LEVEL SECURITY` and tenant-isolation policy DDL
     * (reused from [TenantRlsMigration]), plus the `REVOKE` and the
     * append-only trigger, for `"schema"."table"`.
     *
     * @throws IllegalArgumentException if [schema], [table], [appRole] or a
     *   name derived from [table] (`<table>_immutable`, `<table>_no_mutation`)
     *   is not a plain lower-case SQL identifier of at most 63 characters —
     *   this text is spliced directly into DDL, so every name goes through
     *   [SqlIdentifier.require] (INV-REQ-854 b5, coordinator ruling INV-Q-4036).
     */
    fun trailerDdl(
        schema: String,
        table: String,
        appRole: String,
        policyName: String = "tenant_isolation",
    ): String {
        val schemaName = SqlIdentifier.require(schema, "schema")
        val tableName = SqlIdentifier.require(table, "table")
        val role = SqlIdentifier.require(appRole, "appRole")
        val guardFunction = SqlIdentifier.require("${tableName}_immutable", "guard function")
        val trigger = SqlIdentifier.require("${tableName}_no_mutation", "trigger")
        val immutability =
            """
            |REVOKE UPDATE, DELETE, TRUNCATE ON $schemaName.$tableName FROM $role;
            |CREATE OR REPLACE FUNCTION $schemaName.$guardFunction() RETURNS trigger AS $$
            |BEGIN
            |    RAISE EXCEPTION 'CS-22: % is append-only; % is refused', TG_TABLE_NAME, TG_OP;
            |END;
            |$$ LANGUAGE plpgsql;
            |CREATE TRIGGER $trigger
            |    BEFORE UPDATE OR DELETE ON $schemaName.$tableName
            |    FOR EACH ROW EXECUTE FUNCTION $schemaName.$guardFunction();
            """.trimMargin()
        // Two separate statement groups, joined: tenancy-kernel's RLS trailer is not spliced into this module's SQL.
        return listOf(TenantRlsMigration.ddl(schema, table, policyName), immutability).joinToString("\n")
    }
}
