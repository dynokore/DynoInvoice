-- V202610292340__create_audit_purge.sql: platform-generated DDL (TPL-12); regenerate with GeneratedMigrationsTest, never edit by hand.
-- Generated: audit-log's AuditSchema.purgeDdl("controlplane", "controlplane_runtime", "controlplane_sys", "control-plane-svc") of the platform-libs-bom 0.125.0 train.
grant usage on schema controlplane to controlplane_sys;
grant select, insert, delete on controlplane.audit_entry to controlplane_sys;
create policy audit_entry_sys_select on controlplane.audit_entry for select to controlplane_sys
    using (recorded_at < now() - interval '5 years');
create policy audit_entry_sys_purge on controlplane.audit_entry for delete to controlplane_sys
    using (recorded_at < now() - interval '5 years');
create policy audit_entry_sys_purge_floor on controlplane.audit_entry as restrictive for delete to controlplane_sys
    using (recorded_at < now() - interval '5 years');
create function controlplane.purge_audit_entries(p_cut_off timestamptz, p_reason text, p_entry_id uuid,
    p_source text, p_subject text, p_instance_id text, p_correlation_id text, p_causation_id text)
    returns bigint
language plpgsql security definer set search_path = pg_catalog, controlplane, pg_temp as $$
declare
    v_service constant text := replace('control_plane_svc', '_', '-');
    v_actor text := (select controlplane.bound_actor());
    v_deleted bigint;
begin
    if v_actor is null or v_actor ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]' then
        raise exception 'the bound actor is not set: the purge records its actor' using errcode = '42501';
    end if;
    if (select controlplane.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'the purge runs under the platform tenant only' using errcode = '42501';
    end if;
    if p_cut_off is null or p_cut_off > now() - interval '5 years' then
        raise exception 'cut-off % is later than the legal floor of 5 years', p_cut_off
            using errcode = '22023';
    end if;
    if p_reason is null or p_reason ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' then
        raise exception 'the purge needs a reason' using errcode = '22023';
    end if;
    if p_subject is null or p_subject ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_instance_id is null or p_instance_id ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_correlation_id is null or p_correlation_id ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_causation_id is null or p_causation_id ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' then
        raise exception 'the staged entry needs a subject, instance, correlation and causation'
            using errcode = '22023';
    end if;
    if p_source is distinct from ('/gst/' || v_service) then
        raise exception 'source % is not /gst/%', p_source, v_service using errcode = '22023';
    end if;
    if p_entry_id is null
        or p_entry_id::text !~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' then
        raise exception 'entry id % is not a version-7 UUID', p_entry_id using errcode = '22023';
    end if;
    if exists (select 1 from controlplane.audit_entry where tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid and id = p_entry_id) then
        raise exception 'entry id % is already present', p_entry_id using errcode = '22023';
    end if;
    declare
        v_rows bigint;
        v_oldest timestamptz;
        v_newest timestamptz;
    begin
        select count(*), min(recorded_at), max(recorded_at) into v_rows, v_oldest, v_newest
        from controlplane.audit_entry where recorded_at < p_cut_off;
        insert into controlplane.audit_entry
            (id, tenant_id, entity_ref, recorded_at, source, subject, doc_uuid, transaction_date,
             instance_id, correlation_id, causation_id, actor, action, scope, reason, prior_state)
        values (p_entry_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'ffffffff-ffff-ffff-ffff-ffffffffffff', clock_timestamp(), p_source, p_subject, null, null,
            p_instance_id, p_correlation_id, p_causation_id, v_actor, 'purgeAuditEntries',
            'platform (' || v_service || ' audit_entry)', p_reason,
            case when v_rows = 0 then null else jsonb_build_object('cutOff', p_cut_off, 'rowCount', v_rows,
                'oldest', v_oldest, 'newest', v_newest) end);
        delete from controlplane.audit_entry where recorded_at < p_cut_off;
        get diagnostics v_deleted = row_count;
        return v_deleted;
    end;
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.purge_audit_entries(timestamptz, text, uuid, text, text, text, text, text) owner to controlplane_sys;
revoke all on function controlplane.purge_audit_entries(timestamptz, text, uuid, text, text, text, text, text) from public;
grant execute on function controlplane.purge_audit_entries(timestamptz, text, uuid, text, text, text, text, text) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
