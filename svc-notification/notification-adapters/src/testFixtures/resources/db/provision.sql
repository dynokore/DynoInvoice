-- Provisioning of the test database (INV-T-5972), run by the postgres image's init as superuser after 00-roles.sql.
-- The binding functions are installed here, by the sys login, as the installer does (coordinator rulings
-- INV-Q-6586 and INV-Q-6663); the migrations do not run the generator. ProvisionScriptTest keeps the generated
-- part equal to tenancy-kernel's BindingKeyMigration.ddl.
create schema notification authorization notification_migrate;
create extension if not exists pgcrypto schema public;
grant usage on schema notification to notification_runtime;
grant usage, create on schema notification to notification_sys;
set role notification_sys;
-- BEGIN BindingKeyMigration.ddl
DO $$
BEGIN
    IF current_user <> 'notification_sys' THEN
        RAISE EXCEPTION 'binding functions must be created by notification_sys, not %', current_user;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
        WHERE e.extname = 'pgcrypto' AND n.nspname = 'public') THEN
        RAISE EXCEPTION 'pgcrypto must be installed in schema public';
    END IF;
END $$;
CREATE TABLE notification.binding_key (
    slot   text  PRIMARY KEY CHECK (slot IN ('current', 'previous')),
    secret bytea NOT NULL CHECK (length(secret) > 0)
);
COMMENT ON TABLE notification.binding_key IS 'platform-scoped: no tenant data';
REVOKE ALL ON TABLE notification.binding_key FROM PUBLIC;
CREATE FUNCTION notification.binding_verified() RETURNS text[]
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, notification, pg_temp AS $$
DECLARE
    sig    text := nullif(current_setting('app.binding_sig', true), '');
    parts  text[];
    k      record;
    b64    text;
BEGIN
    IF sig IS NULL THEN RETURN NULL; END IF;
    parts := string_to_array(sig, '.');
    IF array_length(parts, 1) IS DISTINCT FROM 4
       OR parts[1] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR parts[2] !~ '^[A-Za-z0-9_-]+$'
       OR parts[3] !~ '^[0-9]{1,12}$'
       OR parts[4] !~ '^[0-9a-f]{64}$' THEN
        RETURN NULL;
    END IF;
    IF parts[3]::bigint <= extract(epoch FROM now())::bigint THEN RETURN NULL; END IF;
    FOR k IN SELECT secret FROM notification.binding_key LOOP
        IF encode(public.hmac(convert_to(parts[1] || '.' || parts[2] || '.' || parts[3], 'UTF8'),
                               k.secret, 'sha256'), 'hex') = parts[4] THEN
            b64 := translate(parts[2], '-_', '+/');
            b64 := rpad(b64, length(b64) + (4 - length(b64) % 4) % 4, '=');
            RETURN ARRAY[parts[1], convert_from(decode(b64, 'base64'), 'UTF8')];
        END IF;
    END LOOP;
    RETURN NULL;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$;
CREATE FUNCTION notification.bound_tenant() RETURNS uuid
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, notification, pg_temp AS $$
DECLARE
    sig    text := current_setting('app.binding_sig', true);
    body   text;
    actor  text;
    hexb   text;
BEGIN
    IF sig IS NULL OR length(sig) < 70 OR substr(sig, length(sig) - 64, 1) <> '.' THEN RETURN NULL; END IF;
    body := left(sig, length(sig) - 65);
    IF NOT EXISTS (
        SELECT 1 FROM notification.binding_key k
        WHERE encode(public.hmac(convert_to(body, 'UTF8'), k.secret, 'sha256'), 'hex') = right(sig, 64)
    ) THEN RETURN NULL; END IF;
    IF body !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[A-Za-z0-9_-]+\.[0-9]{1,12}$' THEN RETURN NULL; END IF;
    actor := split_part(body, '.', 2);
    IF length(actor) % 4 = 1 THEN RETURN NULL; END IF;
    IF split_part(body, '.', 3)::bigint <= extract(epoch FROM now())::bigint THEN RETURN NULL; END IF;
    actor := translate(actor, '-_', '+/');
    hexb := encode(decode(rpad(actor, length(actor) + (4 - length(actor) % 4) % 4, '='), 'base64'), 'hex');
    IF hexb !~ '^(0[1-9a-f]|[1-7][0-9a-f])*$' AND hexb !~ '^(0[1-9a-f]|[1-7][0-9a-f]|(c[2-9a-f]|d[0-9a-f])[89ab][0-9a-f]|e0[ab][0-9a-f][89ab][0-9a-f]|(e[1-9a-c]|e[ef])[89ab][0-9a-f][89ab][0-9a-f]|ed[89][0-9a-f][89ab][0-9a-f]|f0[9ab][0-9a-f]([89ab][0-9a-f]){2}|f[1-3]([89ab][0-9a-f]){3}|f48[0-9a-f]([89ab][0-9a-f]){2})*$' THEN RETURN NULL; END IF;
    RETURN left(body, 36)::uuid;
END;
$$;
CREATE FUNCTION notification.bound_actor() RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, notification, pg_temp
    AS $$
    SELECT (notification.binding_verified())[2]
$$;
ALTER FUNCTION notification.binding_verified() OWNER TO notification_sys;
ALTER FUNCTION notification.bound_tenant() OWNER TO notification_sys;
ALTER FUNCTION notification.bound_actor() OWNER TO notification_sys;
REVOKE ALL ON FUNCTION notification.binding_verified() FROM PUBLIC;
REVOKE ALL ON FUNCTION notification.bound_tenant() FROM PUBLIC;
REVOKE ALL ON FUNCTION notification.bound_actor() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification.bound_tenant() TO notification_runtime;
GRANT EXECUTE ON FUNCTION notification.bound_actor() TO notification_runtime;
-- END BindingKeyMigration.ddl
insert into notification.binding_key (slot, secret) values ('current', decode('6e6f74696669636174696f6e2d746573742d62696e64696e672d6b65792d30303031', 'hex'));
reset role;
revoke create on schema notification from notification_sys;
