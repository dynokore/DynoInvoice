SET lock_timeout = '5s';
-- V202611161100__register_platform_pack_registered_at.sql (INV-T-4977, registry clock).
-- registered_at was DEFAULT clock_timestamp() and the function did not take it, so a test (and any caller with an
-- injected clock) could not fix the start of the activation window. Forward migration, V1 untouched: the nine-parameter
-- function is replaced by one with a tenth parameter p_registered_at; NULL keeps the database time.
-- Run as rules_migrate (member of rules_sys).
DROP FUNCTION rules.register_platform_pack(uuid, text, text, text, text, date, date, text, text);
CREATE FUNCTION rules.register_platform_pack(p_id uuid, p_pack_id text, p_pack_type text, p_pack_version text,
    p_status text, p_effective_from date, p_effective_to date, p_min_engine_version text, p_supersedes text,
    p_registered_at timestamptz DEFAULT NULL)
    RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, rules, pg_temp AS $$
DECLARE
    v_actor text := (select rules.bound_actor());
BEGIN
    IF (select rules.bound_tenant()) IS DISTINCT FROM 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid THEN
        RAISE EXCEPTION 'a platform pack is registered under the platform tenant only' USING ERRCODE = '42501';
    END IF;
    IF v_actor IS NULL OR v_actor ~ '^\s*$|[[:cntrl:]]' THEN
        RAISE EXCEPTION 'the bound actor is not set: a platform pack records its actor' USING ERRCODE = '42501';
    END IF;
    IF p_id IS NULL OR p_effective_from IS NULL OR p_status IS NULL
        OR coalesce(p_pack_id, '') ~ '^\s*$' OR coalesce(p_pack_type, '') ~ '^\s*$'
        OR coalesce(p_pack_version, '') ~ '^\s*$' OR coalesce(p_min_engine_version, '') ~ '^\s*$' THEN
        RAISE EXCEPTION 'every pack field but effective_to and supersedes is required and not blank' USING ERRCODE = '22023';
    END IF;
    INSERT INTO rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, effective_from,
        effective_to, min_engine_version, supersedes, registered_by, registered_at)
    VALUES (p_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, p_pack_id, p_pack_type, p_pack_version, 'REGULATORY',
        p_status, p_effective_from, p_effective_to, p_min_engine_version, p_supersedes, v_actor,
        coalesce(p_registered_at, clock_timestamp()));
    RETURN p_id;
END
$$;
GRANT CREATE ON SCHEMA rules TO rules_sys;
ALTER FUNCTION rules.register_platform_pack(uuid, text, text, text, text, date, date, text, text, timestamptz) OWNER TO rules_sys;
REVOKE CREATE ON SCHEMA rules FROM rules_sys;
REVOKE EXECUTE ON FUNCTION rules.register_platform_pack(uuid, text, text, text, text, date, date, text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION rules.register_platform_pack(uuid, text, text, text, text, date, date, text, text, timestamptz) TO rules_runtime;
