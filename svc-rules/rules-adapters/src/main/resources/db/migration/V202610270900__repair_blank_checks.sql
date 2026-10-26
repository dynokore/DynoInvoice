SET lock_timeout = '5s';
-- V202610270900__repair_blank_checks.sql (INV-T-10867, INV-AC-10874; INV-REQ-854).
-- Flyway drops every U+FEFF it reads from a script, so the blank checks that V2 and V3 built lost it.
-- Forward repair, V2 and V3 untouched: the generator's corrected text (blank class written as \uXXXX
-- escapes) re-creates each affected function and the CHECK constraint of rules.pack_artefact.
-- Run as rules_migrate (member of rules_sys). BlankCheckRepairMigrationTest pins this file to the generator.
create or replace function rules.full_sync_delete(p_consumer text, p_source text) returns bigint
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
declare
    deleted bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_source is null or p_source ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' then
        raise exception 'a full sync names one consumer and one source' using errcode = '22023';
    end if;
    delete from rules.consumer_sequence where consumer = p_consumer and source = p_source;
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema rules to rules_sys;
alter function rules.full_sync_delete(text, text) owner to rules_sys;
revoke all on function rules.full_sync_delete(text, text) from public;
grant execute on function rules.full_sync_delete(text, text) to rules_runtime;
revoke create on schema rules from rules_sys;
create or replace function rules.event_dead_letter_record(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
    p_reason text, p_raw text) returns bigint
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
declare
    recorded bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_topic is null or p_log_offset is null
        or p_reason is null or p_raw is null then
        raise exception 'a dead letter names its consumer, topic, offset, reason and record'
            using errcode = '22023';
    end if;
    insert into rules.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw)
    values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid), p_reason, p_raw)
    returning id into recorded;
    return recorded;
end
$$;
grant create on schema rules to rules_sys;
alter function rules.event_dead_letter_record(text, text, bigint, uuid, text, text) owner to rules_sys;
revoke all on function rules.event_dead_letter_record(text, text, bigint, uuid, text, text) from public;
grant execute on function rules.event_dead_letter_record(text, text, bigint, uuid, text, text) to rules_runtime;
revoke create on schema rules from rules_sys;
CREATE OR REPLACE FUNCTION rules.pack_artefact_registered_by_insert() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, rules, pg_temp AS $$
DECLARE
    v_actor text := (select rules.bound_actor());
BEGIN
    IF v_actor IS NULL OR v_actor ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]' THEN
        RAISE EXCEPTION 'the bound actor is not set: a pack row records its registering principal'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.registered_by IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'registered_by must be app.actor' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END
$$;
REVOKE EXECUTE ON FUNCTION rules.pack_artefact_registered_by_insert() FROM PUBLIC;
CREATE OR REPLACE FUNCTION rules.register_platform_pack(p_id uuid, p_pack_id text, p_pack_type text, p_version text,
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
    IF v_actor IS NULL OR v_actor ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]' THEN
        RAISE EXCEPTION 'the bound actor is not set: a platform pack records its actor'
            USING ERRCODE = '42501';
    END IF;
    IF p_id IS NULL OR p_effective_from IS NULL OR p_manifest IS NULL OR p_checksums IS NULL
        OR p_signature IS NULL OR p_contents IS NULL
        OR coalesce(p_pack_id, '') ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        OR coalesce(p_pack_type, '') ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        OR coalesce(p_version, '') ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        OR coalesce(p_min_engine_version, '') ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        OR coalesce(p_trusted_anchor, '') ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' THEN
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
ALTER TABLE rules.pack_artefact DROP CONSTRAINT pack_artefact_registered_by_check, ADD CONSTRAINT pack_artefact_registered_by_check CHECK (registered_by !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]') NOT VALID;
ALTER TABLE rules.pack_artefact VALIDATE CONSTRAINT pack_artefact_registered_by_check;
