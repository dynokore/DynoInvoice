-- Provisioning of the test database (INV-T-5968), run by the postgres image's init as superuser after 00-roles.sql.
-- The binding functions are installed here, by the sys login, as the installer does (coordinator rulings
-- INV-Q-6586 and INV-Q-6663); the migrations do not run the generator. ProvisionScriptTest keeps the generated
-- part equal to tenancy-kernel's BindingKeyMigration.ddl.
create schema ingestion authorization ingestion_migrate;
create extension if not exists pgcrypto schema public;
grant usage on schema ingestion to ingestion_runtime;
grant usage, create on schema ingestion to ingestion_sys;
set role ingestion_sys;
-- BEGIN BindingKeyMigration.ddl
DO $$
BEGIN
    IF current_user <> 'ingestion_sys' THEN
        RAISE EXCEPTION 'binding functions must be created by ingestion_sys, not %', current_user;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
        WHERE e.extname = 'pgcrypto' AND n.nspname = 'public') THEN
        RAISE EXCEPTION 'pgcrypto must be installed in schema public';
    END IF;
END $$;
CREATE TABLE ingestion.binding_key (
    slot   text  PRIMARY KEY CHECK (slot IN ('current', 'previous')),
    secret bytea NOT NULL CHECK (length(secret) > 0)
);
COMMENT ON TABLE ingestion.binding_key IS 'platform-scoped: no tenant data';
REVOKE ALL ON TABLE ingestion.binding_key FROM PUBLIC;
CREATE FUNCTION ingestion.binding_verified() RETURNS text[]
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, ingestion, pg_temp AS $$
DECLARE
    sig    text := nullif(current_setting('app.binding_sig', true), '');
    parts  text[];
    n      int;
    body   text;
    k      record;
    b64    text;
BEGIN
    IF sig IS NULL THEN RETURN NULL; END IF;
    parts := string_to_array(sig, '.');
    n := array_length(parts, 1);
    IF n IS NULL OR n NOT IN (4, 5)
       OR parts[1] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR parts[2] !~ '^[A-Za-z0-9_-]+$'
       OR parts[3] !~ '^[0-9]{1,12}$'
       OR (n = 5 AND parts[4] !~ '^(-|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(,[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})*)$')
       OR parts[n] !~ '^[0-9a-f]{64}$' THEN
        RETURN NULL;
    END IF;
    IF parts[3]::bigint <= extract(epoch FROM now())::bigint THEN RETURN NULL; END IF;
    body := left(sig, length(sig) - 65);
    FOR k IN SELECT secret FROM ingestion.binding_key LOOP
        IF encode(public.hmac(convert_to(body, 'UTF8'), k.secret, 'sha256'), 'hex') = parts[n] THEN
            b64 := translate(parts[2], '-_', '+/');
            b64 := rpad(b64, length(b64) + (4 - length(b64) % 4) % 4, '=');
            RETURN ARRAY[parts[1], convert_from(decode(b64, 'base64'), 'UTF8'),
                          CASE WHEN n = 5 THEN parts[4] END];
        END IF;
    END LOOP;
    RETURN NULL;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$;
CREATE FUNCTION ingestion.bound_tenant() RETURNS uuid
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, ingestion, pg_temp AS $$
DECLARE
    sig    text := current_setting('app.binding_sig', true);
    body   text;
    actor  text;
    hexb   text;
BEGIN
    IF sig IS NULL OR length(sig) < 70 OR substr(sig, length(sig) - 64, 1) <> '.' THEN RETURN NULL; END IF;
    body := left(sig, length(sig) - 65);
    IF NOT EXISTS (
        SELECT 1 FROM ingestion.binding_key k
        WHERE encode(public.hmac(convert_to(body, 'UTF8'), k.secret, 'sha256'), 'hex') = right(sig, 64)
    ) THEN RETURN NULL; END IF;
    IF body !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[A-Za-z0-9_-]+\.[0-9]{1,12}(\.(-|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(,[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})*))?$' THEN RETURN NULL; END IF;
    actor := split_part(body, '.', 2);
    IF length(actor) % 4 = 1 THEN RETURN NULL; END IF;
    IF split_part(body, '.', 3)::bigint <= extract(epoch FROM now())::bigint THEN RETURN NULL; END IF;
    actor := translate(actor, '-_', '+/');
    hexb := encode(decode(rpad(actor, length(actor) + (4 - length(actor) % 4) % 4, '='), 'base64'), 'hex');
    IF hexb !~ '^(0[1-9a-f]|[1-7][0-9a-f])*$' AND hexb !~ '^(0[1-9a-f]|[1-7][0-9a-f]|(c[2-9a-f]|d[0-9a-f])[89ab][0-9a-f]|e0[ab][0-9a-f][89ab][0-9a-f]|(e[1-9a-c]|e[ef])[89ab][0-9a-f][89ab][0-9a-f]|ed[89][0-9a-f][89ab][0-9a-f]|f0[9ab][0-9a-f]([89ab][0-9a-f]){2}|f[1-3]([89ab][0-9a-f]){3}|f48[0-9a-f]([89ab][0-9a-f]){2})*$' THEN RETURN NULL; END IF;
    RETURN left(body, 36)::uuid;
END;
$$;
CREATE FUNCTION ingestion.bound_actor() RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, ingestion, pg_temp
    AS $$
    SELECT (ingestion.binding_verified())[2]
$$;
CREATE FUNCTION ingestion.bound_entities() RETURNS uuid[]
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, ingestion, pg_temp AS $$
DECLARE
    v text[] := ingestion.binding_verified();
BEGIN
    IF v IS NULL OR v[3] IS NULL THEN RETURN NULL; END IF;
    IF v[3] = '-' THEN RETURN ARRAY[]::uuid[]; END IF;
    RETURN string_to_array(v[3], ',')::uuid[];
END;
$$;
ALTER FUNCTION ingestion.binding_verified() OWNER TO ingestion_sys;
ALTER FUNCTION ingestion.bound_tenant() OWNER TO ingestion_sys;
ALTER FUNCTION ingestion.bound_actor() OWNER TO ingestion_sys;
ALTER FUNCTION ingestion.bound_entities() OWNER TO ingestion_sys;
REVOKE ALL ON FUNCTION ingestion.binding_verified() FROM PUBLIC;
REVOKE ALL ON FUNCTION ingestion.bound_tenant() FROM PUBLIC;
REVOKE ALL ON FUNCTION ingestion.bound_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION ingestion.bound_entities() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ingestion.bound_tenant() TO ingestion_runtime;
GRANT EXECUTE ON FUNCTION ingestion.bound_actor() TO ingestion_runtime;
GRANT EXECUTE ON FUNCTION ingestion.bound_entities() TO ingestion_runtime;
-- END BindingKeyMigration.ddl
insert into ingestion.binding_key (slot, secret) values ('current', decode('696e67657374696f6e2d746573742d62696e64696e672d6b65792d30303031', 'hex'));
reset role;
revoke create on schema ingestion from ingestion_sys;
