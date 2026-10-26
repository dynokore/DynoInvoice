-- V202610271000: the optional data region of a tenant (INV-Q-8675 A, INV-REQ-1386, INV-AC-1331, INV-AC-8572). A new
-- nullable column, and create_tenant replaced by the same function with one more trailing argument that stores it; a
-- repeat of the same tenant and contract answers the original row, so the original region. Run by tenancy_migrate.
SET lock_timeout = '5s';

ALTER TABLE tenancy.tenant
    ADD COLUMN data_region text CHECK (data_region IS NULL OR length(data_region) BETWEEN 1 AND 64);

-- The stored row as the function answers it: dataRegion is a member only when a region is stored.
CREATE OR REPLACE FUNCTION tenancy.tenant_json(p_outcome text, p_row tenancy.tenant) RETURNS jsonb
    LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, tenancy, pg_temp AS $$
    SELECT jsonb_strip_nulls(jsonb_build_object(
        'outcome', p_outcome,
        'tenantId', p_row.tenant_id,
        'ref', p_row.ref,
        'legalName', p_row.legal_name,
        'planRef', p_row.plan_ref,
        'contractReference', p_row.contract_reference,
        'structureVersion', p_row.structure_version,
        'dataRegion', p_row.data_region))
$$;

DROP FUNCTION tenancy.create_tenant(uuid, text, text, text, text, uuid);

CREATE FUNCTION tenancy.create_tenant(
    p_tenant_id                uuid,
    p_ref                      text,
    p_legal_name               text,
    p_plan_ref                 text,
    p_contract_reference       text,
    p_paired_production_tenant uuid,
    p_data_region              text)
RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, tenancy, pg_temp AS $$
DECLARE
    platform constant uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff';
    bound    uuid := tenancy.bound_tenant();
    found_row tenancy.tenant%ROWTYPE;
BEGIN
    -- 22023 is the adapter's 400, 42501 its 403.
    IF p_tenant_id IS NULL OR p_tenant_id = platform THEN
        RAISE EXCEPTION 'the tenant id is not creatable' USING ERRCODE = '22023';
    END IF;
    IF p_ref IS NULL OR length(p_ref) NOT BETWEEN 1 AND 64
       OR p_legal_name IS NULL OR length(p_legal_name) NOT BETWEEN 1 AND 200
       OR p_plan_ref IS NULL OR length(p_plan_ref) NOT BETWEEN 1 AND 64
       OR p_contract_reference IS NULL OR length(p_contract_reference) NOT BETWEEN 1 AND 64
       OR (p_data_region IS NOT NULL AND length(p_data_region) NOT BETWEEN 1 AND 64) THEN
        RAISE EXCEPTION 'a tenant field is missing or too long' USING ERRCODE = '22023';
    END IF;
    IF p_paired_production_tenant IS NULL THEN
        IF bound IS DISTINCT FROM platform THEN
            RAISE EXCEPTION 'a tenant is created under the platform binding' USING ERRCODE = '42501';
        END IF;
    ELSE
        IF bound IS DISTINCT FROM p_paired_production_tenant THEN
            RAISE EXCEPTION 'a sibling is created under its production tenant''s binding' USING ERRCODE = '42501';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM tenancy.tenant t WHERE t.tenant_id = p_paired_production_tenant) THEN
            RAISE EXCEPTION 'the paired production tenant does not exist' USING ERRCODE = '22023';
        END IF;
    END IF;

    SELECT * INTO found_row FROM tenancy.tenant t WHERE t.tenant_id = p_tenant_id;
    IF FOUND THEN
        IF found_row.contract_reference = p_contract_reference AND found_row.ref = p_ref THEN
            RETURN tenancy.tenant_json('repeat', found_row);
        END IF;
        RETURN jsonb_build_object('outcome', 'conflict');
    END IF;
    IF EXISTS (SELECT 1 FROM tenancy.tenant t WHERE t.contract_reference = p_contract_reference OR t.ref = p_ref) THEN
        RETURN jsonb_build_object('outcome', 'conflict');
    END IF;
    INSERT INTO tenancy.tenant AS t (tenant_id, ref, legal_name, plan_ref, contract_reference, data_region, structure_version, created_at)
    VALUES (p_tenant_id, p_ref, p_legal_name, p_plan_ref, p_contract_reference, p_data_region, nextval('tenancy.structure_version_seq'), now())
    RETURNING * INTO found_row;
    RETURN tenancy.tenant_json('created', found_row);
END;
$$;

ALTER FUNCTION tenancy.create_tenant(uuid, text, text, text, text, uuid, text) OWNER TO tenancy_sys;
REVOKE ALL ON FUNCTION tenancy.create_tenant(uuid, text, text, text, text, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.create_tenant(uuid, text, text, text, text, uuid, text) TO tenancy_runtime;
