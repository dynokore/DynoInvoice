-- V202610271100: the last-changed instant of a structure node and the installation-level tenant listing, both for the
-- producer replay (INV-T-9096; INV-Q-11715 A, INV-Q-11717 A). Run by tenancy_migrate.
-- changed_at is kept by a row trigger so that no writer can forget it; existing rows take the migration instant, which
-- only over-includes them in a PERIOD replay (a replay is idempotent by structureVersion).
SET lock_timeout = '5s';

ALTER TABLE tenancy.tenant_group ADD COLUMN changed_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE tenancy.entity ADD COLUMN changed_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE tenancy.business_unit ADD COLUMN changed_at timestamptz NOT NULL DEFAULT now();

CREATE FUNCTION tenancy.stamp_changed_at() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    NEW.changed_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER tenant_group_stamp_changed_at BEFORE INSERT OR UPDATE ON tenancy.tenant_group
    FOR EACH ROW EXECUTE FUNCTION tenancy.stamp_changed_at();
CREATE TRIGGER entity_stamp_changed_at BEFORE INSERT OR UPDATE ON tenancy.entity
    FOR EACH ROW EXECUTE FUNCTION tenancy.stamp_changed_at();
CREATE TRIGGER business_unit_stamp_changed_at BEFORE INSERT OR UPDATE ON tenancy.business_unit
    FOR EACH ROW EXECUTE FUNCTION tenancy.stamp_changed_at();

-- The tenants that hold state, for a replay across tenants: tenancy.tenant is under forced row-level security and the
-- runtime login sees no row without a binding, so the ids come from a definer function owned by the sys role (the
-- policy tenant_sys_select serves it; precedent: create_tenant in V202610190915).
CREATE FUNCTION tenancy.list_tenant_ids() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, tenancy, pg_temp AS $$
    SELECT tenant_id FROM tenancy.tenant ORDER BY tenant_id
$$;

ALTER FUNCTION tenancy.list_tenant_ids() OWNER TO tenancy_sys;
REVOKE ALL ON FUNCTION tenancy.list_tenant_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.list_tenant_ids() TO tenancy_runtime;
