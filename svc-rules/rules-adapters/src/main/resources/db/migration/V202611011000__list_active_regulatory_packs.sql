-- The read path of the active regulatory packs for the installer (INV-T-9880, operation listActiveRegulatoryPacks).
-- The installer call carries no tenant binding, and the policy pack_registry_tenant_read admits a platform row only to
-- a bound tenant, so the platform rows are read through one static SECURITY DEFINER function owned by the NOLOGIN
-- rules_sys, with a fixed search_path, the way register_platform_pack and advance_platform_pack are written. The
-- policies of rules.pack_registry are not changed. The function returns platform REGULATORY rows only, so a tenant's
-- row can never come back through it. ARMED is the state of a pack in use today; ACTIVE is admitted for a later value.
-- Order and keyset: (pack_type, pack_version) compared as strings in the C collation; the cursor position is the last
-- pair of the previous page.
CREATE FUNCTION rules.list_active_regulatory_packs(p_limit integer, p_after_type text, p_after_version text)
    RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, rules, pg_temp AS $$
BEGIN
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 501 THEN
        RAISE EXCEPTION 'p_limit must be 1..501' USING ERRCODE = '22023';
    END IF;
    RETURN (SELECT coalesce(jsonb_agg(jsonb_build_object('packType', p.pack_type, 'version', p.pack_version,
                'status', p.status, 'minEngineVersion', p.min_engine_version,
                'effectiveFrom', p.effective_from, 'effectiveTo', p.effective_to)
            ORDER BY p.pack_type COLLATE "C", p.pack_version COLLATE "C"), '[]'::jsonb)
        FROM (SELECT r.pack_type, r.pack_version, r.status, r.min_engine_version, r.effective_from, r.effective_to
            FROM rules.pack_registry r
            WHERE r.tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
                AND r.layer = 'REGULATORY'
                AND r.status IN ('ARMED', 'ACTIVE')
                AND (p_after_type IS NULL
                    OR (r.pack_type COLLATE "C", r.pack_version COLLATE "C")
                        > (p_after_type COLLATE "C", p_after_version COLLATE "C"))
            ORDER BY r.pack_type COLLATE "C", r.pack_version COLLATE "C"
            LIMIT p_limit) p);
END
$$;
GRANT CREATE ON SCHEMA rules TO rules_sys;
ALTER FUNCTION rules.list_active_regulatory_packs(integer, text, text) OWNER TO rules_sys;
REVOKE CREATE ON SCHEMA rules FROM rules_sys;
REVOKE EXECUTE ON FUNCTION rules.list_active_regulatory_packs(integer, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION rules.list_active_regulatory_packs(integer, text, text) TO rules_runtime;
