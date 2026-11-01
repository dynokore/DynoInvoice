-- V202611011000: workspaces and environment separation (INV-REQ-1125, INV-REQ-1130, INV-REQ-1305). Run by tenancy_migrate.
-- A workspace is a SANDBOX tenant row paired to its production tenant (INV-REQ-1125 behavior 1); it is written only by
-- the definer function create_tenant (INV-Q-7468 A), archived only by archive_workspace, and read by workspace_state.
SET lock_timeout = '5s';

ALTER TABLE tenancy.tenant
    ADD COLUMN environment                 text NOT NULL DEFAULT 'PRODUCTION' CHECK (environment IN ('PRODUCTION', 'SANDBOX')),
    ADD COLUMN paired_production_tenant_id uuid REFERENCES tenancy.tenant (tenant_id),
    ADD COLUMN workspace_name              text CHECK (workspace_name IS NULL OR length(workspace_name) BETWEEN 1 AND 64),
    ADD CONSTRAINT tenant_workspace_pairing CHECK (
        (environment = 'SANDBOX' AND paired_production_tenant_id IS NOT NULL AND workspace_name IS NOT NULL)
        OR (environment = 'PRODUCTION' AND paired_production_tenant_id IS NULL AND workspace_name IS NULL)),
    ADD CONSTRAINT tenant_workspace_name_unique UNIQUE (paired_production_tenant_id, workspace_name);

CREATE POLICY tenant_sys_update ON tenancy.tenant FOR UPDATE TO tenancy_sys USING (true) WITH CHECK (true);
GRANT UPDATE (retired) ON tenancy.tenant TO tenancy_sys;

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
        'retired', p_row.retired)
$$;

-- create_tenant gains the workspace name; the pairing argument now also stores the pairing and the SANDBOX environment.
DROP FUNCTION tenancy.create_tenant(uuid, text, text, text, text, uuid, text);

CREATE FUNCTION tenancy.create_tenant(
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
                                     structure_version, created_at, environment, paired_production_tenant_id, workspace_name)
    VALUES (p_tenant_id, p_ref, p_legal_name, p_plan_ref, p_contract_reference, p_data_region,
            nextval('tenancy.structure_version_seq'), now(),
            CASE WHEN p_paired_production_tenant IS NULL THEN 'PRODUCTION' ELSE 'SANDBOX' END,
            p_paired_production_tenant, p_workspace_name)
    RETURNING * INTO found_row;
    RETURN tenancy.tenant_json('created', found_row);
END;
$$;

-- The workspace of the bound production tenant by its ref: the row, or none for a ref that is no workspace of the bound tenant.
CREATE FUNCTION tenancy.workspace_state(p_ref text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, tenancy, pg_temp AS $$
DECLARE
    found_row tenancy.tenant%ROWTYPE;
BEGIN
    SELECT * INTO found_row FROM tenancy.tenant t
     WHERE t.ref = p_ref AND t.environment = 'SANDBOX' AND t.paired_production_tenant_id = tenancy.bound_tenant();
    IF NOT FOUND THEN
        RETURN jsonb_build_object('outcome', 'missing');
    END IF;
    RETURN tenancy.tenant_json('found', found_row);
END;
$$;

-- Archives a workspace of the bound production tenant: 'archived' the first time, 'repeat' after, 'missing' for no workspace.
CREATE FUNCTION tenancy.archive_workspace(p_ref text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, tenancy, pg_temp AS $$
DECLARE
    found_row tenancy.tenant%ROWTYPE;
BEGIN
    SELECT * INTO found_row FROM tenancy.tenant t
     WHERE t.ref = p_ref AND t.environment = 'SANDBOX' AND t.paired_production_tenant_id = tenancy.bound_tenant()
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('outcome', 'missing');
    END IF;
    IF found_row.retired THEN
        RETURN tenancy.tenant_json('repeat', found_row);
    END IF;
    UPDATE tenancy.tenant t SET retired = true WHERE t.tenant_id = found_row.tenant_id RETURNING * INTO found_row;
    RETURN tenancy.tenant_json('archived', found_row);
END;
$$;

ALTER FUNCTION tenancy.create_tenant(uuid, text, text, text, text, uuid, text, text) OWNER TO tenancy_sys;
REVOKE ALL ON FUNCTION tenancy.create_tenant(uuid, text, text, text, text, uuid, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.create_tenant(uuid, text, text, text, text, uuid, text, text) TO tenancy_runtime;
ALTER FUNCTION tenancy.workspace_state(text) OWNER TO tenancy_sys;
REVOKE ALL ON FUNCTION tenancy.workspace_state(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.workspace_state(text) TO tenancy_runtime;
ALTER FUNCTION tenancy.archive_workspace(text) OWNER TO tenancy_sys;
REVOKE ALL ON FUNCTION tenancy.archive_workspace(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.archive_workspace(text) TO tenancy_runtime;

-- One approval per production-sample copy, recorded by a principal holding security.officer (INV-REQ-1130 behavior 4).
CREATE TABLE tenancy.sample_copy_approval (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    sample_ref  text        NOT NULL CHECK (length(sample_ref) BETWEEN 1 AND 200),
    approved_by text        NOT NULL CHECK (length(approved_by) BETWEEN 1 AND 200),
    approved_at timestamptz NOT NULL,
    consumed_at timestamptz
);

CREATE INDEX sample_copy_approval_open ON tenancy.sample_copy_approval (tenant_id, sample_ref) WHERE consumed_at IS NULL;

ALTER TABLE tenancy.sample_copy_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.sample_copy_approval FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.sample_copy_approval
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON tenancy.sample_copy_approval TO tenancy_runtime;
