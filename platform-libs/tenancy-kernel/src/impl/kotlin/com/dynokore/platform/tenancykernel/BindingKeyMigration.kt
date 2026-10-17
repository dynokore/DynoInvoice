package com.dynokore.platform.tenancykernel

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * The signed-binding half of a service's schema (INV-REQ-854 b7, owner ruling INV-Q-4121; slice 1 of INV-T-4156): the
 * key table `<schema>.binding_key` and the STABLE `SECURITY DEFINER` functions `<schema>.bound_tenant()` and
 * `<schema>.bound_actor()` that verify `app.binding_sig` with pgcrypto and return NULL for a missing, malformed, forged
 * or expired value. The value layout is in README.md ("Signed binding"). [ddl] is pure text generation like
 * [TenantRlsMigration.ddl]; [writeKeys] is the migration runner's key write. Nothing calls the functions yet.
 */
object BindingKeyMigration {
    private const val SLOT_CURRENT = "current"
    private const val SLOT_PREVIOUS = "previous"

    /**
     * The key table, the functions and their grants for [schema], to run as [sysRole] (`<svc>_sys`): the statements
     * make [sysRole] the owner, and the first block refuses to run as any other login. The table is readable by its
     * owner only; the functions run with the owner's rights, with `search_path = pg_catalog, pg_temp` and pgcrypto
     * called by its schema-qualified name [pgcryptoSchema], so no schema a caller controls is searched. EXECUTE is
     * revoked from PUBLIC and granted to [runtimeRole] only.
     *
     * pgcrypto must be installed in [pgcryptoSchema] before; the first block fails the migration when it is not.
     *
     * @throws IllegalArgumentException if a name is not a plain lower-case identifier ([SqlIdentifier.require]).
     */
    fun ddl(
        schema: String,
        sysRole: String,
        runtimeRole: String,
        pgcryptoSchema: String = "public",
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val sys = SqlIdentifier.require(sysRole, "sysRole")
        val runtime = SqlIdentifier.require(runtimeRole, "runtimeRole")
        val crypto = SqlIdentifier.require(pgcryptoSchema, "pgcryptoSchema")
        return """
            |DO ${'$'}guard${'$'}
            |BEGIN
            |    IF current_user <> '$sys' THEN
            |        RAISE EXCEPTION 'binding functions must be created by $sys, not %', current_user;
            |    END IF;
            |    IF NOT EXISTS (
            |        SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
            |        WHERE e.extname = 'pgcrypto' AND n.nspname = '$crypto') THEN
            |        RAISE EXCEPTION 'pgcrypto must be installed in schema $crypto';
            |    END IF;
            |END ${'$'}guard${'$'};
            |CREATE TABLE $s.binding_key (
            |    slot   text  PRIMARY KEY CHECK (slot IN ('$SLOT_CURRENT', '$SLOT_PREVIOUS')),
            |    secret bytea NOT NULL CHECK (length(secret) > 0)
            |);
            |REVOKE ALL ON TABLE $s.binding_key FROM PUBLIC;
            |CREATE FUNCTION $s.binding_verified() RETURNS text[]
            |    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS ${'$'}fn${'$'}
            |${verifyBody(s, crypto)}
            |${'$'}fn${'$'};
            |CREATE FUNCTION $s.bound_tenant() RETURNS uuid
            |    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
            |    AS ${'$'}fn${'$'}SELECT ($s.binding_verified())[1]::uuid${'$'}fn${'$'};
            |CREATE FUNCTION $s.bound_actor() RETURNS text
            |    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
            |    AS ${'$'}fn${'$'}SELECT ($s.binding_verified())[2]${'$'}fn${'$'};
            |REVOKE ALL ON FUNCTION $s.binding_verified() FROM PUBLIC;
            |REVOKE ALL ON FUNCTION $s.bound_tenant() FROM PUBLIC;
            |REVOKE ALL ON FUNCTION $s.bound_actor() FROM PUBLIC;
            |GRANT EXECUTE ON FUNCTION $s.bound_tenant() TO $runtime;
            |GRANT EXECUTE ON FUNCTION $s.bound_actor() TO $runtime;
            """.trimMargin()
    }

    /**
     * The migration runner's key write: upserts the current key into slot `current` and the previous key into slot
     * `previous` (or deletes that slot when [source] has none), through [dsl] on the migration's own connection and
     * transaction, as the key table's owner.
     *
     * @throws IllegalStateException if [source] has no current key (null or empty); nothing is written then.
     */
    fun writeKeys(
        dsl: DSLContext,
        schema: String,
        source: BindingKeySource,
    ) {
        val current = source.currentKey()
        check(
            current != null && current.isNotEmpty(),
        ) { "BindingKeySource has no current binding key; migration refused" }
        val previous = source.previousKey()?.takeIf { it.isNotEmpty() }
        val table = DSL.table(DSL.name(SqlIdentifier.require(schema, "schema"), "binding_key"))
        val slot = DSL.field(DSL.name("slot"), SQLDataType.VARCHAR)
        val secret = DSL.field(DSL.name("secret"), SQLDataType.VARBINARY)
        for ((name, key) in listOf(SLOT_CURRENT to current, SLOT_PREVIOUS to previous)) {
            if (key == null) {
                dsl.deleteFrom(table).where(slot.eq(name)).execute()
            } else {
                dsl
                    .insertInto(table, slot, secret)
                    .values(name, key)
                    .onConflict(slot)
                    .doUpdate()
                    .set(secret, key)
                    .execute()
            }
        }
    }

    private fun verifyBody(
        schema: String,
        crypto: String,
    ): String =
        """
        |DECLARE
        |    sig    text := nullif(current_setting('app.binding_sig', true), '');
        |    parts  text[];
        |    k      record;
        |    b64    text;
        |BEGIN
        |    IF sig IS NULL THEN RETURN NULL; END IF;
        |    parts := string_to_array(sig, '.');
        |    IF array_length(parts, 1) IS DISTINCT FROM 4
        |       OR parts[1] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}${'$'}'
        |       OR parts[2] !~ '^[A-Za-z0-9_-]+${'$'}'
        |       OR parts[3] !~ '^[0-9]{1,12}${'$'}'
        |       OR parts[4] !~ '^[0-9a-f]{64}${'$'}' THEN
        |        RETURN NULL;
        |    END IF;
        |    IF parts[3]::bigint <= extract(epoch FROM now())::bigint THEN RETURN NULL; END IF;
        |    FOR k IN SELECT secret FROM $schema.binding_key LOOP
        |        IF encode($crypto.hmac(convert_to(parts[1] || '.' || parts[2] || '.' || parts[3], 'UTF8'),
        |                               k.secret, 'sha256'), 'hex') = parts[4] THEN
        |            b64 := translate(parts[2], '-_', '+/');
        |            b64 := rpad(b64, length(b64) + (4 - length(b64) % 4) % 4, '=');
        |            RETURN ARRAY[parts[1], convert_from(decode(b64, 'base64'), 'UTF8')];
        |        END IF;
        |    END LOOP;
        |    RETURN NULL;
        |EXCEPTION WHEN OTHERS THEN
        |    RETURN NULL;
        |END;
        """.trimMargin()
}
