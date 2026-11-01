-- V202611011100: the tenant row's last change and a new structure version on archive (INV-Q-15377 A, INV-REQ-1125).
-- Run by tenancy_migrate. Existing rows take the migration instant, which only over-includes them in a period replay.
SET lock_timeout = '5s';

ALTER TABLE tenancy.tenant ADD COLUMN changed_at timestamptz NOT NULL DEFAULT now();

-- stamp_changed_at() is the trigger function of V202610271100 (group, entity, business unit).
CREATE TRIGGER tenant_stamp_changed_at BEFORE INSERT OR UPDATE ON tenancy.tenant
    FOR EACH ROW EXECUTE FUNCTION tenancy.stamp_changed_at();

-- archive_workspace now also stores a new structure version, so the sys role may update that column too.
GRANT UPDATE (structure_version) ON tenancy.tenant TO tenancy_sys;

-- Archives a workspace of the bound production tenant: 'archived' the first time (a new structure version is drawn and
-- stored in the same statement that sets retired), 'repeat' after, 'missing' for no workspace. Only the first draws.
CREATE OR REPLACE FUNCTION tenancy.archive_workspace(p_ref text) RETURNS jsonb
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
    UPDATE tenancy.tenant t
       SET retired = true, structure_version = nextval('tenancy.structure_version_seq')
     WHERE t.tenant_id = found_row.tenant_id
    RETURNING * INTO found_row;
    RETURN tenancy.tenant_json('archived', found_row);
END;
$$;

ALTER FUNCTION tenancy.archive_workspace(text) OWNER TO tenancy_sys;
REVOKE ALL ON FUNCTION tenancy.archive_workspace(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.archive_workspace(text) TO tenancy_runtime;
