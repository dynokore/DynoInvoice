-- V202611091500: the tenant state SCHEDULED, OPEN, CLOSED (INV-REQ-8100, owner answer INV-Q-8097 option B). Run by tenancy_migrate.
-- Expand, backfill, then default: every tenant row that exists is OPEN (a migration changes no behaviour of existing rows),
-- the column default is then SCHEDULED, and create_tenant states the state explicitly: SCHEDULED for a production tenant,
-- OPEN for a workspace (a paired SANDBOX tenant).
-- A created tenant is SCHEDULED. It is opened only by open_tenant (platform binding) and closed only by close_tenant (the
-- tenant's own binding, after a COMPLETED whole-tenant offboarding record); the runtime login has no UPDATE on the column.
-- No CHECK on the value: the go-live gate compares the text by equality with OPEN, so a stray value is never open.
SET lock_timeout = '5s';

ALTER TABLE tenancy.tenant ADD COLUMN state text NOT NULL DEFAULT 'OPEN';
ALTER TABLE tenancy.tenant ALTER COLUMN state SET DEFAULT 'SCHEDULED';
GRANT UPDATE (state) ON tenancy.tenant TO tenancy_sys;

CREATE OR REPLACE FUNCTION tenancy.tenant_json(p_outcome text, p_row tenancy.tenant) RETURNS jsonb
    LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, tenancy, pg_temp AS $$
    SELECT jsonb_build_object(
        'outcome', p_outcome,
        'tenantId', p_row.tenant_id,
        'ref', p_row.ref,
        'legalName', p_row.legal_name,
        'planRef', p_row.plan_ref,
        'contractReference', p_row.contract_reference,
        'structureVersion', p_row.structure_version,
        'dataRegion', p_row.data_region,
        'environment', p_row.environment,
        'pairedProductionTenant', p_row.paired_production_tenant_id,
        'workspaceName', p_row.workspace_name,
        'retired', p_row.retired,
        'state', p_row.state)
$$;

-- create_tenant (V202611011000) inserts the state explicitly; signature, owner and grants are unchanged.
CREATE OR REPLACE FUNCTION tenancy.create_tenant(
    p_tenant_id                uuid,
    p_ref                      text,
    p_legal_name               text,
    p_plan_ref                 text,
    p_contract_reference       text,
    p_paired_production_tenant uuid,
    p_data_region              text,
    p_workspace_name           text DEFAULT NULL)
RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, tenancy, pg_temp AS $$
DECLARE
    platform constant uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff';
    bound    uuid := tenancy.bound_tenant();
    found_row tenancy.tenant%ROWTYPE;
    production tenancy.tenant%ROWTYPE;
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
        IF p_workspace_name IS NOT NULL THEN
            RAISE EXCEPTION 'a production tenant has no workspace name' USING ERRCODE = '22023';
        END IF;
    ELSE
        IF bound IS DISTINCT FROM p_paired_production_tenant THEN
            RAISE EXCEPTION 'a sibling is created under its production tenant''s binding' USING ERRCODE = '42501';
        END IF;
        SELECT * INTO production FROM tenancy.tenant t WHERE t.tenant_id = p_paired_production_tenant;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'the paired production tenant does not exist' USING ERRCODE = '22023';
        END IF;
        IF production.environment <> 'PRODUCTION' THEN
            RAISE EXCEPTION 'a workspace is paired to a production tenant' USING ERRCODE = '22023';
        END IF;
        IF p_workspace_name IS NULL OR length(p_workspace_name) NOT BETWEEN 1 AND 64 THEN
            RAISE EXCEPTION 'a workspace has a name' USING ERRCODE = '22023';
        END IF;
    END IF;

    SELECT * INTO found_row FROM tenancy.tenant t WHERE t.tenant_id = p_tenant_id;
    IF FOUND THEN
        IF found_row.contract_reference = p_contract_reference AND found_row.ref = p_ref
           AND found_row.paired_production_tenant_id IS NOT DISTINCT FROM p_paired_production_tenant
           AND found_row.workspace_name IS NOT DISTINCT FROM p_workspace_name THEN
            RETURN tenancy.tenant_json('repeat', found_row);
        END IF;
        RETURN jsonb_build_object('outcome', 'conflict');
    END IF;
    IF EXISTS (SELECT 1 FROM tenancy.tenant t WHERE t.contract_reference = p_contract_reference OR t.ref = p_ref) THEN
        RETURN jsonb_build_object('outcome', 'conflict');
    END IF;
    INSERT INTO tenancy.tenant AS t (tenant_id, ref, legal_name, plan_ref, contract_reference, data_region,
                                     structure_version, created_at, environment, paired_production_tenant_id, workspace_name,
                                     state)
    VALUES (p_tenant_id, p_ref, p_legal_name, p_plan_ref, p_contract_reference, p_data_region,
            nextval('tenancy.structure_version_seq'), now(),
            CASE WHEN p_paired_production_tenant IS NULL THEN 'PRODUCTION' ELSE 'SANDBOX' END,
            p_paired_production_tenant, p_workspace_name,
            CASE WHEN p_paired_production_tenant IS NULL THEN 'SCHEDULED' ELSE 'OPEN' END)
    RETURNING * INTO found_row;
    RETURN tenancy.tenant_json('created', found_row);
END;
$$;

-- SCHEDULED to OPEN for the tenant with this ref: 'opened', 'repeat' (already OPEN), 'refused' (any other state), 'missing'.
CREATE FUNCTION tenancy.open_tenant(p_ref text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, tenancy, pg_temp AS $$
DECLARE
    platform constant uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff';
    found_row tenancy.tenant%ROWTYPE;
BEGIN
    IF tenancy.bound_tenant() IS DISTINCT FROM platform THEN
        RAISE EXCEPTION 'a tenant is opened under the platform binding' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO found_row FROM tenancy.tenant t WHERE t.ref = p_ref FOR UPDATE;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('outcome', 'missing');
    END IF;
    IF found_row.state = 'OPEN' THEN
        RETURN tenancy.tenant_json('repeat', found_row);
    END IF;
    IF found_row.state <> 'SCHEDULED' THEN
        RETURN tenancy.tenant_json('refused', found_row);
    END IF;
    UPDATE tenancy.tenant t SET state = 'OPEN' WHERE t.tenant_id = found_row.tenant_id RETURNING * INTO found_row;
    RETURN tenancy.tenant_json('opened', found_row);
END;
$$;

-- CLOSED for the bound tenant only; a repeat changes nothing.
CREATE FUNCTION tenancy.close_tenant() RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, tenancy, pg_temp AS $$
DECLARE
    platform constant uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff';
    bound    uuid := tenancy.bound_tenant();
    found_row tenancy.tenant%ROWTYPE;
BEGIN
    IF bound IS NULL OR bound = platform THEN
        RAISE EXCEPTION 'a tenant is closed under its own binding' USING ERRCODE = '42501';
    END IF;
    UPDATE tenancy.tenant t SET state = 'CLOSED'
     WHERE t.tenant_id = bound AND t.state <> 'CLOSED' RETURNING * INTO found_row;
    RETURN jsonb_build_object('outcome', CASE WHEN found_row.tenant_id IS NULL THEN 'unchanged' ELSE 'closed' END);
END;
$$;

ALTER FUNCTION tenancy.open_tenant(text) OWNER TO tenancy_sys;
REVOKE ALL ON FUNCTION tenancy.open_tenant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.open_tenant(text) TO tenancy_runtime;
ALTER FUNCTION tenancy.close_tenant() OWNER TO tenancy_sys;
REVOKE ALL ON FUNCTION tenancy.close_tenant() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.close_tenant() TO tenancy_runtime;
