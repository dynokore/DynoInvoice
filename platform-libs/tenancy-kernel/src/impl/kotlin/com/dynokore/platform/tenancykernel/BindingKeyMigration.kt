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
     * The signed body `<tenant>.<actor>.<expiry>` in one pattern, applied after the MAC comparison (INV-Q-7088 (a)).
     */
    private const val BODY_SHAPE =
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9_-]+\\.[0-9]{1,12}$"

    /**
     * Hex of ASCII bytes 01 to 7F: the fast path before the full RFC 3629 test (INV-Q-7088 (b)); it accepts no NUL
     * byte.
     */
    private const val ASCII_NO_NUL_HEX = "^(0[1-9a-f]|[1-7][0-9a-f])*$"

    /**
     * Hex of a byte string that is well-formed UTF-8 (RFC 3629: no overlong form, no surrogate, nothing above
     * U+10FFFF) without a NUL byte: the check `bound_tenant()` makes on the decoded actor part without an exception
     * clause. Every branch starts with a different lead byte, so the pattern does not backtrack.
     */
    private const val UTF8_NO_NUL_HEX =
        "^(" +
            "0[1-9a-f]|[1-7][0-9a-f]" +
            "|(c[2-9a-f]|d[0-9a-f])[89ab][0-9a-f]" +
            "|e0[ab][0-9a-f][89ab][0-9a-f]" +
            "|(e[1-9a-c]|e[ef])[89ab][0-9a-f][89ab][0-9a-f]" +
            "|ed[89][0-9a-f][89ab][0-9a-f]" +
            "|f0[9ab][0-9a-f]([89ab][0-9a-f]){2}" +
            "|f[1-3]([89ab][0-9a-f]){3}" +
            "|f48[0-9a-f]([89ab][0-9a-f]){2}" +
            ")*\$"

    /**
     * The key table, the functions and their grants for [schema], to run as [sysRole] (`<svc>_sys`): the statements
     * make [sysRole] the owner, and the first block refuses to run as any other login. The table is readable by its
     * owner only; the functions run with the owner's rights, with `search_path = pg_catalog, <schema>, pg_temp`
     * (INV-Q-4038) and pgcrypto called by its schema-qualified name [pgcryptoSchema]; a caller cannot create objects in
     * either schema. EXECUTE is revoked from PUBLIC and granted to [runtimeRole] only.
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
        // Each part checks the names it uses; these four checks keep the order in which a bad name is refused.
        SqlIdentifier.require(schema, "schema")
        SqlIdentifier.require(sysRole, "sysRole")
        SqlIdentifier.require(runtimeRole, "runtimeRole")
        SqlIdentifier.require(pgcryptoSchema, "pgcryptoSchema")
        return listOf(
            keyTable(schema, sysRole, pgcryptoSchema),
            verifiedFunction(schema, pgcryptoSchema),
            boundTenantFunction(schema, pgcryptoSchema),
            boundActorFunction(schema, sysRole, runtimeRole),
        ).joinToString("\n")
    }

    private fun keyTable(
        schema: String,
        sysRole: String,
        pgcryptoSchema: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val sys = SqlIdentifier.require(sysRole, "sysRole")
        val crypto = SqlIdentifier.require(pgcryptoSchema, "pgcryptoSchema")
        return """
            |DO $$
            |BEGIN
            |    IF current_user <> '$sys' THEN
            |        RAISE EXCEPTION 'binding functions must be created by $sys, not %', current_user;
            |    END IF;
            |    IF NOT EXISTS (
            |        SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
            |        WHERE e.extname = 'pgcrypto' AND n.nspname = '$crypto') THEN
            |        RAISE EXCEPTION 'pgcrypto must be installed in schema $crypto';
            |    END IF;
            |END $$;
            |CREATE TABLE $s.binding_key (
            |    slot   text  PRIMARY KEY CHECK (slot IN ('$SLOT_CURRENT', '$SLOT_PREVIOUS')),
            |    secret bytea NOT NULL CHECK (length(secret) > 0)
            |);
            |COMMENT ON TABLE $s.binding_key IS 'platform-scoped: no tenant data';
            |REVOKE ALL ON TABLE $s.binding_key FROM PUBLIC;
            """.trimMargin()
    }

    private fun verifiedFunction(
        schema: String,
        pgcryptoSchema: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val crypto = SqlIdentifier.require(pgcryptoSchema, "pgcryptoSchema")
        return """
            |CREATE FUNCTION $s.binding_verified() RETURNS text[]
            |    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, $s, pg_temp AS $$
            |DECLARE
            |    sig    text := nullif(current_setting('app.binding_sig', true), '');
            |    parts  text[];
            |    k      record;
            |    b64    text;
            |BEGIN
            |    IF sig IS NULL THEN RETURN NULL; END IF;
            |    parts := string_to_array(sig, '.');
            |    IF array_length(parts, 1) IS DISTINCT FROM 4
            |       OR parts[1] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            |       OR parts[2] !~ '^[A-Za-z0-9_-]+$'
            |       OR parts[3] !~ '^[0-9]{1,12}$'
            |       OR parts[4] !~ '^[0-9a-f]{64}$' THEN
            |        RETURN NULL;
            |    END IF;
            |    IF parts[3]::bigint <= extract(epoch FROM now())::bigint THEN RETURN NULL; END IF;
            |    FOR k IN SELECT secret FROM $s.binding_key LOOP
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
            |$$;
            """.trimMargin()
    }

    private fun boundTenantFunction(
        schema: String,
        pgcryptoSchema: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val crypto = SqlIdentifier.require(pgcryptoSchema, "pgcryptoSchema")
        return """
            |CREATE FUNCTION $s.bound_tenant() RETURNS uuid
            |    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, $s, pg_temp AS $$
            |DECLARE
            |    sig    text := current_setting('app.binding_sig', true);
            |    body   text;
            |    actor  text;
            |    hexb   text;
            |BEGIN
            |    IF sig IS NULL OR length(sig) < 70 OR substr(sig, length(sig) - 64, 1) <> '.' THEN RETURN NULL; END IF;
            |    body := left(sig, length(sig) - 65);
            |    IF NOT EXISTS (
            |        SELECT 1 FROM $s.binding_key k
            |        WHERE encode($crypto.hmac(convert_to(body, 'UTF8'), k.secret, 'sha256'), 'hex') = right(sig, 64)
            |    ) THEN RETURN NULL; END IF;
            |    IF body !~ '$BODY_SHAPE' THEN RETURN NULL; END IF;
            |    actor := split_part(body, '.', 2);
            |    IF length(actor) % 4 = 1 THEN RETURN NULL; END IF;
            |    IF split_part(body, '.', 3)::bigint <= extract(epoch FROM now())::bigint THEN RETURN NULL; END IF;
            |    actor := translate(actor, '-_', '+/');
            |    hexb := encode(decode(rpad(actor, length(actor) + (4 - length(actor) % 4) % 4, '='), 'base64'), 'hex');
            |    IF hexb !~ '$ASCII_NO_NUL_HEX' AND hexb !~ '$UTF8_NO_NUL_HEX' THEN RETURN NULL; END IF;
            |    RETURN left(body, 36)::uuid;
            |END;
            |$$;
            """.trimMargin()
    }

    private fun boundActorFunction(
        schema: String,
        sysRole: String,
        runtimeRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val sys = SqlIdentifier.require(sysRole, "sysRole")
        val runtime = SqlIdentifier.require(runtimeRole, "runtimeRole")
        return """
            |CREATE FUNCTION $s.bound_actor() RETURNS text
            |    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, $s, pg_temp
            |    AS $$
            |    SELECT ($s.binding_verified())[2]
            |$$;
            |ALTER FUNCTION $s.binding_verified() OWNER TO $sys;
            |ALTER FUNCTION $s.bound_tenant() OWNER TO $sys;
            |ALTER FUNCTION $s.bound_actor() OWNER TO $sys;
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
}
