package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.BlankText
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The DDL for a pack holder's own persisted [PackRegistry] table (INV-REQ-1195: "each pack holder … holds it locally,
 * in its own schema or memory"), generated instead of hand-written so the shape stays identical across `rules-svc`
 * and every pack-holder service embedding this kernel. Every pack holder stores exactly the same columns (STK-13's
 * artefact members plus the identity and effective-dating fields of [PackManifest]), so this DDL owns the whole
 * `CREATE TABLE`, its row-level security and its one platform write path.
 *
 * The table holds platform-wide pack rows and a tenant's own rows (the `Mapping` pack type is
 * `tenant_overridable: Yes`) side by side. `tenant_id` is `NOT NULL` (INV-REQ-854 b2, CS-19): a platform-wide row
 * carries the reserved platform tenant [TenantId.PLATFORM] (owner ruling INV-Q-2927 D; coordinator ruling
 * INV-Q-4117). A [PackVersion] with no tenant is stored as a platform row.
 *
 * Row-level security, enabled and forced, keyed on the transaction's signed tenant binding only (INV-REQ-854 b1, b7;
 * read through `<schema>.bound_tenant()` of tenancy-kernel's `BindingKeyMigration`, which this DDL does not run;
 * no other session setting widens it, INV-ADR-3968 alternatives):
 * - SELECT: the bound tenant's rows plus the platform rows; nothing when no tenant is bound. Under the platform
 *   binding that is the platform rows.
 * - INSERT, UPDATE, DELETE: only the bound tenant's own rows, never a platform row, so under the platform binding
 *   the runtime login writes nothing directly. Rows are immutable (INV-ADR-263, INV-REQ-1089): the holder grants its
 *   runtime login `INSERT, SELECT` only; the policy refuses a platform-row write even where a grant would allow it.
 * - Platform rows are inserted only by [REGISTER_PLATFORM_PACK], a SECURITY DEFINER function owned by the NOLOGIN
 *   `<svc>_sys` role (INV-ADR-3968 item 6): static SQL, `SET search_path = pg_catalog, <schema>, pg_temp`, EXECUTE
 *   revoked from PUBLIC and granted to the runtime login only, the actor taken from the signed actor binding
 *   (`<schema>.bound_actor()`; refused when unset,
 *   blank or holding a control character, nothing written) and stored as the row's `registered_by`, every argument
 *   validated inside. Its own policy lets the sys role insert a platform row and nothing else.
 * - Every row's `registered_by` is the registering principal, the transaction's bound actor at insert, and never
 *   changes afterwards (owner ruling INV-Q-4580 (B)): a BEFORE INSERT trigger refuses any other value, a BEFORE
 *   UPDATE trigger any change.
 *
 * For tenancy-jooq the table is class `REFERENCE` (INV-ADR-3968 item 4a): the holder declares [referenceTable] in its
 * `TableClasses.of(reference = …)`, so a read returns the tenant's and the platform rows and jOOQ never writes a
 * platform row. Resolution reads the tenant's rows before the platform rows ([PackRegistry.resolve],
 * INV-REQ-1195/INV-REQ-770, unchanged).
 */
object PackRegistryMigration {
    /** The platform write path's function, `<schema>.register_platform_pack(...)` (coordinator ruling INV-Q-4117). */
    const val REGISTER_PLATFORM_PACK = "register_platform_pack"

    /**
     * The function's argument types in call order: id, pack id, pack type, version, effective from, effective to
     * (null when open-ended), minimum engine version, manifest, checksums, signature, contents, trusted anchor. It
     * returns the stored row's `id`.
     */
    const val REGISTER_PLATFORM_PACK_ARGUMENTS =
        "uuid, text, text, text, date, date, text, jsonb, jsonb, bytea, jsonb, text"

    /** [TenantId.PLATFORM], as SQL text (INV-Q-2927 D, INV-Q-3012). */
    internal const val PLATFORM = "ffffffff-ffff-ffff-ffff-ffffffffffff"

    init {
        check(PLATFORM == TenantId.PLATFORM.toString()) { "PackRegistryMigration.PLATFORM must be TenantId.PLATFORM" }
    }

    /**
     * The qualified name (`schema.table`) a holder declares as a `REFERENCE` table in tenancy-jooq's
     * `TableClasses.of(reference = …)` (INV-ADR-3968 item 4a). The holder must declare it: undeclared, the table is
     * classified TENANT (the item 4a default) and a tenant's reads silently drop the platform rows; the holder's own
     * runtime test proves it (INV-MOD-53; coordinator ruling INV-Q-4596).
     */
    fun referenceTable(
        schema: String,
        table: String,
    ): String = "${SqlIdentifier.require(schema, "schema")}.${SqlIdentifier.require(table, "table")}"

    /**
     * `CREATE TABLE "schema"."table"`, its lineage key, resolution index, row-level security and the
     * [REGISTER_PLATFORM_PACK] function, appended into the holder's own Flyway migration (CS-19 naming,
     * forward-only), run as `<svc>_migrate`, which owns the table. [sysRole] (NOLOGIN) must exist and the migrating
     * role must be a member of it (it needs SET ROLE on it for `ALTER FUNCTION ... OWNER TO`, as every module's
     * `<svc>_sys`-owned function); [runtimeRole] is the service's runtime login.
     *
     * Precondition (coordinator ruling INV-Q-6663): this DDL does not create the signed-binding functions and issues no
     * role switch. The schema must already hold `<schema>.bound_tenant()` and `<schema>.bound_actor()`, from
     * tenancy-kernel's `BindingKeyMigration.ddl` run once per schema as the sys login, before this DDL; on a schema
     * without them the first policy that names `bound_tenant()` raises (SQLSTATE 42883) and nothing is created. Who
     * runs
     * `BindingKeyMigration.ddl` in an installation is INV-Q-6580 (owner, unanswered).
     *
     * @throws IllegalArgumentException if [schema], [table], a role or a name derived from [table] is not a plain
     *   lower-case SQL identifier of at most 63 characters (spliced into DDL text, so every name goes through
     *   [SqlIdentifier.require], INV-REQ-854 b5), or if the two roles are the same.
     */
    fun ddl(
        schema: String,
        table: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        require(runtime != sys) { "the runtime login and the sys role must differ, both were '$runtime'" }
        return listOf(
            tableDdl(s, t, sys),
            registeredBy(s, t),
            registerPlatformPack(s, t, runtime, sys),
        ).joinToString("\n")
    }

    private fun tableDdl(
        schema: String,
        table: String,
        sysRole: String,
    ): String = PackRegistryTableDdl.create(schema, table) + "\n" + tablePolicies(schema, table, sysRole)

    private fun tablePolicies(
        schema: String,
        table: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val read = SqlIdentifier.require("${t}_tenant_read", "policy")
        val insert = SqlIdentifier.require("${t}_tenant_insert", "policy")
        val update = SqlIdentifier.require("${t}_tenant_update", "policy")
        val delete = SqlIdentifier.require("${t}_tenant_delete", "policy")
        val register = SqlIdentifier.require("${t}_platform_register", "policy")
        // The bound tenant: an absent, forged or expired signed binding binds nothing, so no row matches.
        val boundTenant = "(select $s.bound_tenant())"
        val ownRow = "(tenant_id = $boundTenant AND tenant_id <> '$PLATFORM'::uuid)"
        return """
            |ALTER TABLE $s.$t ENABLE ROW LEVEL SECURITY;
            |ALTER TABLE $s.$t FORCE ROW LEVEL SECURITY;
            |CREATE POLICY $read ON $s.$t FOR SELECT
            |    USING (tenant_id = $boundTenant OR ($boundTenant IS NOT NULL AND tenant_id = '$PLATFORM'::uuid));
            |CREATE POLICY $insert ON $s.$t FOR INSERT WITH CHECK $ownRow;
            |CREATE POLICY $update ON $s.$t FOR UPDATE USING $ownRow WITH CHECK $ownRow;
            |CREATE POLICY $delete ON $s.$t FOR DELETE USING $ownRow;
            |CREATE POLICY $register ON $s.$t FOR INSERT TO $sys WITH CHECK (tenant_id = '$PLATFORM'::uuid);
            |GRANT USAGE ON SCHEMA $s TO $sys;
            |GRANT INSERT ON $s.$t TO $sys;
            """.trimMargin()
    }

    /**
     * `registered_by` is the registering principal (owner ruling INV-Q-4580 (B)): a BEFORE INSERT trigger refuses a row
     * whose `registered_by` is not the transaction's bound actor (an unset actor, or one that is blank or holds a
     * control character, [BlankText.SQL_NOT_ACTOR], refuses every insert), and a BEFORE UPDATE trigger refuses any
     * change to it, so it is fixed at insert as the actor of `write_installation_setting`'s audit entry is
     * (INV-T-3983). Static SQL, fixed search_path, EXECUTE revoked from PUBLIC (a trigger function's EXECUTE is checked
     * when the trigger is created, never when it fires).
     */
    private fun registeredBy(
        schema: String,
        table: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        val stampFunction = SqlIdentifier.require("${t}_registered_by_insert", "trigger function")
        val fixFunction = SqlIdentifier.require("${t}_registered_by_update", "trigger function")
        return """
        |CREATE FUNCTION $s.$stampFunction() RETURNS trigger
        |LANGUAGE plpgsql SET search_path = pg_catalog, $s, pg_temp AS $$
        |DECLARE
        |    v_actor text := (select $s.bound_actor());
        |BEGIN
        |    IF v_actor IS NULL OR v_actor ~ '${BlankText.SQL_NOT_ACTOR}' THEN
        |        RAISE EXCEPTION 'the bound actor is not set: a pack row records its registering principal'
        |            USING ERRCODE = '42501';
        |    END IF;
        |    IF NEW.registered_by IS DISTINCT FROM v_actor THEN
        |        RAISE EXCEPTION 'registered_by must be app.actor' USING ERRCODE = '42501';
        |    END IF;
        |    RETURN NEW;
        |END
        |$$;
        |CREATE FUNCTION $s.$fixFunction() RETURNS trigger
        |LANGUAGE plpgsql SET search_path = pg_catalog, $s, pg_temp AS $$
        |BEGIN
        |    IF NEW.registered_by IS DISTINCT FROM OLD.registered_by THEN
        |        RAISE EXCEPTION 'registered_by is fixed at insert' USING ERRCODE = '42501';
        |    END IF;
        |    RETURN NEW;
        |END
        |$$;
        |REVOKE EXECUTE ON FUNCTION $s.$stampFunction() FROM PUBLIC;
        |REVOKE EXECUTE ON FUNCTION $s.$fixFunction() FROM PUBLIC;
        |CREATE TRIGGER $stampFunction BEFORE INSERT ON $s.$t
        |    FOR EACH ROW EXECUTE FUNCTION $s.$stampFunction();
        |CREATE TRIGGER $fixFunction BEFORE UPDATE ON $s.$t
        |    FOR EACH ROW EXECUTE FUNCTION $s.$fixFunction();
            """.trimMargin()
    }

    /** The platform write path (INV-ADR-3968 item 6; coordinator ruling INV-Q-4117). */
    private fun registerPlatformPack(
        schema: String,
        table: String,
        runtimeRole: String,
        sysRole: String,
    ): String =
        listOf(platformPackFunction(schema, table), platformPackGrants(schema, runtimeRole, sysRole)).joinToString("\n")

    private fun platformPackFunction(
        schema: String,
        table: String,
    ): String =
        listOf(
            platformPackHead(schema),
            platformPackChecks(schema),
            platformPackInsert(schema, table),
        ).joinToString("\n")

    private fun platformPackHead(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            |CREATE FUNCTION $s.$REGISTER_PLATFORM_PACK(p_id uuid, p_pack_id text, p_pack_type text, p_version text,
            |    p_effective_from date, p_effective_to date, p_min_engine_version text, p_manifest jsonb,
            |    p_checksums jsonb, p_signature bytea, p_contents jsonb, p_trusted_anchor text)
            |    RETURNS uuid
            |LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, $s, pg_temp AS $$
            |DECLARE
            |    v_actor text := (select $s.bound_actor());
            |BEGIN
            """.trimMargin()
    }

    private fun platformPackChecks(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            |    IF (select $s.bound_tenant()) IS DISTINCT FROM '$PLATFORM'::uuid THEN
            |        RAISE EXCEPTION 'a platform pack is registered under the platform tenant only'
            |            USING ERRCODE = '42501';
            |    END IF;
            |    IF v_actor IS NULL OR v_actor ~ '${BlankText.SQL_NOT_ACTOR}' THEN
            |        RAISE EXCEPTION 'the bound actor is not set: a platform pack records its actor'
            |            USING ERRCODE = '42501';
            |    END IF;
            |    IF p_id IS NULL OR p_effective_from IS NULL OR p_manifest IS NULL OR p_checksums IS NULL
            |        OR p_signature IS NULL OR p_contents IS NULL
            |        OR coalesce(p_pack_id, '') ~ '${BlankText.SQL_BLANK}'
            |        OR coalesce(p_pack_type, '') ~ '${BlankText.SQL_BLANK}'
            |        OR coalesce(p_version, '') ~ '${BlankText.SQL_BLANK}'
            |        OR coalesce(p_min_engine_version, '') ~ '${BlankText.SQL_BLANK}'
            |        OR coalesce(p_trusted_anchor, '') ~ '${BlankText.SQL_BLANK}' THEN
            |        RAISE EXCEPTION 'every pack field but effective_to is required and not blank'
            |            USING ERRCODE = '22023';
            |    END IF;
            |    IF p_effective_to IS NOT NULL AND p_effective_to <= p_effective_from THEN
            |        RAISE EXCEPTION 'effective_to must be after effective_from' USING ERRCODE = '22023';
            |    END IF;
            |    IF jsonb_typeof(p_manifest) <> 'object' OR jsonb_typeof(p_checksums) <> 'object'
            |        OR octet_length(p_signature) = 0 THEN
            |        RAISE EXCEPTION 'the manifest and checksums are JSON objects and the signature is not empty'
            |            USING ERRCODE = '22023';
            |    END IF;
            """.trimMargin()
    }

    private fun platformPackInsert(
        schema: String,
        table: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        return """
            |    INSERT INTO $s.$t (id, pack_id, pack_type, pack_version, tenant_id, effective_from, effective_to,
            |        min_engine_version, manifest, checksums, signature, contents, trusted_anchor, registered_by)
            |    VALUES (p_id, p_pack_id, p_pack_type, p_version, '$PLATFORM'::uuid, p_effective_from, p_effective_to,
            |        p_min_engine_version, p_manifest, p_checksums, p_signature, p_contents, p_trusted_anchor, v_actor);
            |    RETURN p_id;
            |END
            |$$;
            """.trimMargin()
    }

    private fun platformPackGrants(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            |GRANT CREATE ON SCHEMA $s TO $sys;
            |ALTER FUNCTION $s.$REGISTER_PLATFORM_PACK($REGISTER_PLATFORM_PACK_ARGUMENTS) OWNER TO $sys;
            |REVOKE EXECUTE ON FUNCTION $s.$REGISTER_PLATFORM_PACK($REGISTER_PLATFORM_PACK_ARGUMENTS) FROM PUBLIC;
            |GRANT EXECUTE ON FUNCTION $s.$REGISTER_PLATFORM_PACK($REGISTER_PLATFORM_PACK_ARGUMENTS) TO $runtime;
            |REVOKE CREATE ON SCHEMA $s FROM $sys;
            """.trimMargin()
    }
}
