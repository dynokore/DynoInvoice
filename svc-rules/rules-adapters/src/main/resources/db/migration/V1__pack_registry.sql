-- V1__pack_registry.sql (TPL-12, CS-19, ARC-20, INV-REQ-1196; INV-T-4965)
-- Run only by the service's migration Job through service-runtime's MigrationRunner as rules_migrate, which owns the
-- schema and every table; never at service start-up. The schema `rules` is created and put on the search path by
-- Flyway; the roles rules_migrate, rules_runtime (the one runtime LOGIN) and rules_sys (NOLOGIN) exist before it runs.
-- PRECONDITION (tenancy-kernel BindingKeyMigration): rules.bound_tenant() and rules.bound_actor() exist in the schema,
-- created once per schema by the sys login; who runs that is owner question INV-Q-6580.
--
-- The pack registry holds METADATA and ACTIVATION STATE only (INV-REQ-1196, INV-AC-2182): which packs exist, which are
-- armed, their effective dates and the supersession chain. It holds no manifest, no signature, no checksums and no
-- contents: rules-svc is not a pack repository that others read.
CREATE TABLE rules.pack_registry (
    id                 uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id          uuid        NOT NULL,                                     -- the reserved platform tenant for regulatory packs
    pack_id            text        NOT NULL CHECK (pack_id !~ '^\s*$'),
    pack_type          text        NOT NULL CHECK (pack_type !~ '^\s*$'),
    pack_version       text        NOT NULL CHECK (pack_version !~ '^\s*$'),
    layer              text        NOT NULL CHECK (layer IN ('REGULATORY', 'TENANT_MAPPING')),
    status             text        NOT NULL CHECK (status IN
        ('DRAFT', 'SUBMITTED', 'APPROVED', 'STAGED', 'ACKNOWLEDGED', 'ARMED', 'SUPERSEDED', 'ABORTED')),
    effective_from     date        NOT NULL,
    effective_to       date        NULL CHECK (effective_to IS NULL OR effective_to > effective_from),
    min_engine_version text        NOT NULL CHECK (min_engine_version !~ '^\s*$'),
    supersedes         text        NULL,                                         -- pack_version of the predecessor
    registered_by      text        NOT NULL CHECK (registered_by !~ '^\s*$|[[:cntrl:]]'),
    registered_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pack_registry_lineage_key UNIQUE (pack_type, tenant_id, pack_version),
    CONSTRAINT pack_registry_layer_tenant CHECK
        ((layer = 'REGULATORY') = (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)),
    CONSTRAINT pack_registry_no_self_supersession CHECK (supersedes IS DISTINCT FROM pack_version)
);
CREATE INDEX pack_registry_resolution_idx ON rules.pack_registry (pack_type, tenant_id, effective_from);

-- The source citation and source class of each entry (INV-REQ-727, INV-REQ-774, INV-AC-1727). A class is required: an
-- entry without one is never stored, so it is never read as PRIMARY (coordinator ruling INV-Q-6477).
CREATE TABLE rules.pack_entry_source (
    id              uuid   PRIMARY KEY,
    tenant_id       uuid   NOT NULL,
    pack_type       text   NOT NULL,
    pack_version    text   NOT NULL,
    entry_id        text   NOT NULL CHECK (entry_id !~ '^\s*$'),
    source_class    text   NOT NULL CHECK (source_class IN ('PRIMARY', 'SECONDARY', 'INFERRED')),
    source_citation text   NULL CHECK (source_citation IS NULL OR source_citation !~ '^\s*$'),
    accepted        boolean NOT NULL DEFAULT false,
    CONSTRAINT pack_entry_source_key UNIQUE (pack_type, tenant_id, pack_version, entry_id),
    FOREIGN KEY (pack_type, tenant_id, pack_version)
        REFERENCES rules.pack_registry (pack_type, tenant_id, pack_version)
);

ALTER TABLE rules.pack_registry ENABLE ROW LEVEL SECURITY;
ALTER TABLE rules.pack_registry FORCE ROW LEVEL SECURITY;                        -- the owner rules_migrate is also bound
ALTER TABLE rules.pack_entry_source ENABLE ROW LEVEL SECURITY;
ALTER TABLE rules.pack_entry_source FORCE ROW LEVEL SECURITY;

-- Signed binding (INV-T-4156, INV-REQ-854 b7): the bound tenant comes from the verified binding only; an absent, forged
-- or expired binding binds nothing, so no row matches (fails closed).
-- pack_registry: a bound tenant reads its own rows and the platform rows; writes only its own rows, never a platform row.
CREATE POLICY pack_registry_tenant_read ON rules.pack_registry FOR SELECT
    USING (tenant_id = (select rules.bound_tenant())
        OR ((select rules.bound_tenant()) IS NOT NULL AND tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid));
CREATE POLICY pack_registry_tenant_insert ON rules.pack_registry FOR INSERT
    WITH CHECK (tenant_id = (select rules.bound_tenant()) AND tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
CREATE POLICY pack_registry_tenant_update ON rules.pack_registry FOR UPDATE
    USING (tenant_id = (select rules.bound_tenant()) AND tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
    WITH CHECK (tenant_id = (select rules.bound_tenant()) AND tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
-- The platform rows are written only by the sys-owned functions below (INV-ADR-3968 item 6, INV-T-4137).
CREATE POLICY pack_registry_platform_register ON rules.pack_registry FOR INSERT TO rules_sys
    WITH CHECK (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
CREATE POLICY pack_registry_platform_select ON rules.pack_registry FOR SELECT TO rules_sys
    USING (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
CREATE POLICY pack_registry_platform_advance ON rules.pack_registry FOR UPDATE TO rules_sys
    USING (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
    WITH CHECK (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);

-- pack_entry_source: a bound tenant reads and writes its own rows and reads the platform rows.
CREATE POLICY pack_entry_source_tenant_read ON rules.pack_entry_source FOR SELECT
    USING (tenant_id = (select rules.bound_tenant())
        OR ((select rules.bound_tenant()) IS NOT NULL AND tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid));
CREATE POLICY pack_entry_source_tenant_insert ON rules.pack_entry_source FOR INSERT
    WITH CHECK (tenant_id = (select rules.bound_tenant()) AND tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
CREATE POLICY pack_entry_source_tenant_update ON rules.pack_entry_source FOR UPDATE
    USING (tenant_id = (select rules.bound_tenant()) AND tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
    WITH CHECK (tenant_id = (select rules.bound_tenant()) AND tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
CREATE POLICY pack_entry_source_platform_register ON rules.pack_entry_source FOR INSERT TO rules_sys
    WITH CHECK (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);

-- registered_by is the registering principal, the transaction's bound actor at insert, and never changes afterwards.
CREATE FUNCTION rules.pack_registry_registered_by_insert() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, rules, pg_temp AS $$
DECLARE
    v_actor text := (select rules.bound_actor());
BEGIN
    IF v_actor IS NULL OR v_actor ~ '^\s*$|[[:cntrl:]]' THEN
        RAISE EXCEPTION 'the bound actor is not set: a pack row records its registering principal'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.registered_by IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'registered_by must be the bound actor' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END
$$;
-- A pack version is never edited in place once armed (INV-REQ-773, INV-AC-1726): the content columns, the effective
-- dates and the supersession link are frozen from ARMED on, an armed version never returns to an earlier state, and the
-- only change left is the move to SUPERSEDED or ABORTED. registered_by and the identity never change at any time.
CREATE FUNCTION rules.pack_registry_frozen_update() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, rules, pg_temp AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.pack_id IS DISTINCT FROM OLD.pack_id OR NEW.pack_type IS DISTINCT FROM OLD.pack_type
        OR NEW.pack_version IS DISTINCT FROM OLD.pack_version OR NEW.layer IS DISTINCT FROM OLD.layer
        OR NEW.registered_by IS DISTINCT FROM OLD.registered_by OR NEW.registered_at IS DISTINCT FROM OLD.registered_at THEN
        RAISE EXCEPTION 'the identity of a pack version is fixed at insert' USING ERRCODE = '42501';
    END IF;
    IF OLD.status IN ('ARMED', 'SUPERSEDED', 'ABORTED') THEN
        IF NEW.effective_from IS DISTINCT FROM OLD.effective_from OR NEW.min_engine_version IS DISTINCT FROM OLD.min_engine_version
            OR NEW.supersedes IS DISTINCT FROM OLD.supersedes THEN
            RAISE EXCEPTION 'an armed pack version is never edited in place; author a superseding version'
                USING ERRCODE = '42501';
        END IF;
        IF NEW.effective_to IS DISTINCT FROM OLD.effective_to AND OLD.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'an armed pack version keeps its effective-to date' USING ERRCODE = '42501';
        END IF;
        IF NEW.status IS DISTINCT FROM OLD.status AND NOT (OLD.status = 'ARMED' AND NEW.status IN ('SUPERSEDED', 'ABORTED')) THEN
            RAISE EXCEPTION 'there is no way back from %', OLD.status USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
-- The entries of a pack version are frozen with it.
CREATE FUNCTION rules.pack_entry_source_frozen() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, rules, pg_temp AS $$
DECLARE
    v_status text;
BEGIN
    SELECT p.status INTO v_status FROM rules.pack_registry p
        WHERE p.pack_type = COALESCE(NEW.pack_type, OLD.pack_type) AND p.tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id)
            AND p.pack_version = COALESCE(NEW.pack_version, OLD.pack_version);
    IF v_status IN ('ARMED', 'SUPERSEDED', 'ABORTED') THEN
        RAISE EXCEPTION 'the entries of an armed pack version are never edited in place' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE(NEW, OLD);
END
$$;
REVOKE EXECUTE ON FUNCTION rules.pack_registry_registered_by_insert() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION rules.pack_registry_frozen_update() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION rules.pack_entry_source_frozen() FROM PUBLIC;
CREATE TRIGGER pack_registry_registered_by_insert BEFORE INSERT ON rules.pack_registry
    FOR EACH ROW EXECUTE FUNCTION rules.pack_registry_registered_by_insert();
CREATE TRIGGER pack_registry_frozen_update BEFORE UPDATE ON rules.pack_registry
    FOR EACH ROW EXECUTE FUNCTION rules.pack_registry_frozen_update();
CREATE TRIGGER pack_entry_source_frozen BEFORE INSERT OR UPDATE OR DELETE ON rules.pack_entry_source
    FOR EACH ROW EXECUTE FUNCTION rules.pack_entry_source_frozen();

-- The platform write path (INV-ADR-3968 item 6, INV-T-4137): static SQL, SECURITY DEFINER, owned by the NOLOGIN
-- rules_sys, with a fixed search_path; EXECUTE is revoked from PUBLIC and granted to the runtime login only. The signed
-- binding must be the platform tenant's, and the actor is taken from the signed actor binding.
CREATE FUNCTION rules.register_platform_pack(p_id uuid, p_pack_id text, p_pack_type text, p_pack_version text,
    p_status text, p_effective_from date, p_effective_to date, p_min_engine_version text, p_supersedes text)
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
        effective_to, min_engine_version, supersedes, registered_by)
    VALUES (p_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, p_pack_id, p_pack_type, p_pack_version, 'REGULATORY',
        p_status, p_effective_from, p_effective_to, p_min_engine_version, p_supersedes, v_actor);
    RETURN p_id;
END
$$;
-- The activation state of a platform pack moves only through this function, which keeps the transition rules in the
-- frozen-update trigger and never edits another column.
CREATE FUNCTION rules.advance_platform_pack(p_pack_type text, p_pack_version text, p_status text,
    p_effective_to date)
    RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, rules, pg_temp AS $$
DECLARE
    v_actor text := (select rules.bound_actor());
BEGIN
    IF (select rules.bound_tenant()) IS DISTINCT FROM 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid THEN
        RAISE EXCEPTION 'a platform pack is advanced under the platform tenant only' USING ERRCODE = '42501';
    END IF;
    IF v_actor IS NULL OR v_actor ~ '^\s*$|[[:cntrl:]]' THEN
        RAISE EXCEPTION 'the bound actor is not set: advancing a platform pack records its actor' USING ERRCODE = '42501';
    END IF;
    UPDATE rules.pack_registry SET status = p_status, effective_to = coalesce(p_effective_to, effective_to)
        WHERE pack_type = p_pack_type AND pack_version = p_pack_version
            AND tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'no such platform pack version' USING ERRCODE = '02000';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA rules TO rules_sys;
GRANT SELECT, INSERT, UPDATE ON rules.pack_registry TO rules_sys;
GRANT INSERT ON rules.pack_entry_source TO rules_sys;
GRANT CREATE ON SCHEMA rules TO rules_sys;
ALTER FUNCTION rules.register_platform_pack(uuid, text, text, text, text, date, date, text, text) OWNER TO rules_sys;
ALTER FUNCTION rules.advance_platform_pack(text, text, text, date) OWNER TO rules_sys;
REVOKE CREATE ON SCHEMA rules FROM rules_sys;
REVOKE EXECUTE ON FUNCTION rules.register_platform_pack(uuid, text, text, text, text, date, date, text, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION rules.advance_platform_pack(text, text, text, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION rules.register_platform_pack(uuid, text, text, text, text, date, date, text, text) TO rules_runtime;
GRANT EXECUTE ON FUNCTION rules.advance_platform_pack(text, text, text, date) TO rules_runtime;

-- The runtime login owns nothing. A tenant's own rows may be inserted and moved through their states; no row is ever
-- deleted, so a superseded version stays resolvable for earlier dates (INV-REQ-1089).
GRANT USAGE ON SCHEMA rules TO rules_runtime;
GRANT SELECT, INSERT, UPDATE ON rules.pack_registry TO rules_runtime;
GRANT SELECT, INSERT, UPDATE ON rules.pack_entry_source TO rules_runtime;
