-- Generated: PackRegistryMigration.ddl("rules", "pack_artefact", "rules_runtime", "rules_sys") of
-- rulepack-kernel (V3__pack_artefact.sql, TPL-12, INV-T-7525), committed as text;
-- MigrationDdlTest fails when it differs. The holder copy of the verified packs (INV-REQ-1195),
-- beside the metadata tables of V1 and replacing none of them (INV-Q-7438). Run as rules_migrate;
-- PRECONDITION: rules.bound_tenant() and rules.bound_actor() exist (tenancy-kernel BindingKeyMigration).
CREATE TABLE rules.pack_artefact (
    id                 uuid PRIMARY KEY,
    pack_id            text NOT NULL,
    pack_type          text NOT NULL,
    pack_version       text NOT NULL,
    tenant_id          uuid NOT NULL,
    effective_from     date NOT NULL,
    effective_to       date NULL,
    min_engine_version text NOT NULL,
    manifest           jsonb NOT NULL,
    checksums          jsonb NOT NULL,
    signature          bytea NOT NULL,
    contents           jsonb NOT NULL,
    trusted_anchor     text NOT NULL,
    registered_by      text NOT NULL CHECK (registered_by !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$|[\u0001-\u001F\u007F-\u009F]'),
    created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pack_artefact_lineage_key UNIQUE (pack_type, tenant_id, pack_version)
);
CREATE INDEX pack_artefact_resolution_idx ON rules.pack_artefact (pack_type, tenant_id, effective_from);
ALTER TABLE rules.pack_artefact ENABLE ROW LEVEL SECURITY;
ALTER TABLE rules.pack_artefact FORCE ROW LEVEL SECURITY;
CREATE POLICY pack_artefact_tenant_read ON rules.pack_artefact FOR SELECT
    USING (tenant_id = (select rules.bound_tenant()) OR ((select rules.bound_tenant()) IS NOT NULL AND tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid));
CREATE POLICY pack_artefact_tenant_insert ON rules.pack_artefact FOR INSERT WITH CHECK (tenant_id = (select rules.bound_tenant()) AND tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
CREATE POLICY pack_artefact_tenant_update ON rules.pack_artefact FOR UPDATE USING (tenant_id = (select rules.bound_tenant()) AND tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid) WITH CHECK (tenant_id = (select rules.bound_tenant()) AND tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
CREATE POLICY pack_artefact_tenant_delete ON rules.pack_artefact FOR DELETE USING (tenant_id = (select rules.bound_tenant()) AND tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
CREATE POLICY pack_artefact_platform_register ON rules.pack_artefact FOR INSERT TO rules_sys WITH CHECK (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
GRANT USAGE ON SCHEMA rules TO rules_sys;
GRANT INSERT ON rules.pack_artefact TO rules_sys;
CREATE FUNCTION rules.pack_artefact_registered_by_insert() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, rules, pg_temp AS $$
DECLARE
    v_actor text := (select rules.bound_actor());
BEGIN
    IF v_actor IS NULL OR v_actor ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$|[\u0001-\u001F\u007F-\u009F]' THEN
        RAISE EXCEPTION 'the bound actor is not set: a pack row records its registering principal'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.registered_by IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'registered_by must be app.actor' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END
$$;
CREATE FUNCTION rules.pack_artefact_registered_by_update() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, rules, pg_temp AS $$
BEGIN
    IF NEW.registered_by IS DISTINCT FROM OLD.registered_by THEN
        RAISE EXCEPTION 'registered_by is fixed at insert' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END
$$;
REVOKE EXECUTE ON FUNCTION rules.pack_artefact_registered_by_insert() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION rules.pack_artefact_registered_by_update() FROM PUBLIC;
CREATE TRIGGER pack_artefact_registered_by_insert BEFORE INSERT ON rules.pack_artefact
    FOR EACH ROW EXECUTE FUNCTION rules.pack_artefact_registered_by_insert();
CREATE TRIGGER pack_artefact_registered_by_update BEFORE UPDATE ON rules.pack_artefact
    FOR EACH ROW EXECUTE FUNCTION rules.pack_artefact_registered_by_update();
CREATE FUNCTION rules.register_platform_pack(p_id uuid, p_pack_id text, p_pack_type text, p_version text,
    p_effective_from date, p_effective_to date, p_min_engine_version text, p_manifest jsonb,
    p_checksums jsonb, p_signature bytea, p_contents jsonb, p_trusted_anchor text)
    RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, rules, pg_temp AS $$
DECLARE
    v_actor text := (select rules.bound_actor());
BEGIN
    IF (select rules.bound_tenant()) IS DISTINCT FROM 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid THEN
        RAISE EXCEPTION 'a platform pack is registered under the platform tenant only'
            USING ERRCODE = '42501';
    END IF;
    IF v_actor IS NULL OR v_actor ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$|[\u0001-\u001F\u007F-\u009F]' THEN
        RAISE EXCEPTION 'the bound actor is not set: a platform pack records its actor'
            USING ERRCODE = '42501';
    END IF;
    IF p_id IS NULL OR p_effective_from IS NULL OR p_manifest IS NULL OR p_checksums IS NULL
        OR p_signature IS NULL OR p_contents IS NULL
        OR coalesce(p_pack_id, '') ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        OR coalesce(p_pack_type, '') ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        OR coalesce(p_version, '') ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        OR coalesce(p_min_engine_version, '') ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
        OR coalesce(p_trusted_anchor, '') ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$' THEN
        RAISE EXCEPTION 'every pack field but effective_to is required and not blank'
            USING ERRCODE = '22023';
    END IF;
    IF p_effective_to IS NOT NULL AND p_effective_to <= p_effective_from THEN
        RAISE EXCEPTION 'effective_to must be after effective_from' USING ERRCODE = '22023';
    END IF;
    IF jsonb_typeof(p_manifest) <> 'object' OR jsonb_typeof(p_checksums) <> 'object'
        OR octet_length(p_signature) = 0 THEN
        RAISE EXCEPTION 'the manifest and checksums are JSON objects and the signature is not empty'
            USING ERRCODE = '22023';
    END IF;
    INSERT INTO rules.pack_artefact (id, pack_id, pack_type, pack_version, tenant_id, effective_from, effective_to,
        min_engine_version, manifest, checksums, signature, contents, trusted_anchor, registered_by)
    VALUES (p_id, p_pack_id, p_pack_type, p_version, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, p_effective_from, p_effective_to,
        p_min_engine_version, p_manifest, p_checksums, p_signature, p_contents, p_trusted_anchor, v_actor);
    RETURN p_id;
END
$$;
GRANT CREATE ON SCHEMA rules TO rules_sys;
ALTER FUNCTION rules.register_platform_pack(uuid, text, text, text, date, date, text, jsonb, jsonb, bytea, jsonb, text) OWNER TO rules_sys;
REVOKE EXECUTE ON FUNCTION rules.register_platform_pack(uuid, text, text, text, date, date, text, jsonb, jsonb, bytea, jsonb, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION rules.register_platform_pack(uuid, text, text, text, date, date, text, jsonb, jsonb, bytea, jsonb, text) TO rules_runtime;
REVOKE CREATE ON SCHEMA rules FROM rules_sys;
