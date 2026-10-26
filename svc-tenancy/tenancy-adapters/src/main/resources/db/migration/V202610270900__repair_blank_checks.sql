SET lock_timeout = '5s';

-- Restores U+FEFF (written as \uFEFF) in the blank checks Flyway built: Flyway 12 drops raw U+FEFF from a script (INV-T-10836).

create or replace function tenancy.full_sync_delete(p_consumer text, p_source text) returns bigint
language plpgsql security definer set search_path = pg_catalog, tenancy, pg_temp as $$
declare
    deleted bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_source is null or p_source ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' then
        raise exception 'a full sync names one consumer and one source' using errcode = '22023';
    end if;
    delete from tenancy.consumer_sequence where consumer = p_consumer and source = p_source;
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema tenancy to tenancy_sys;
alter function tenancy.full_sync_delete(text, text) owner to tenancy_sys;
revoke all on function tenancy.full_sync_delete(text, text) from public;
grant execute on function tenancy.full_sync_delete(text, text) to tenancy_runtime;
revoke create on schema tenancy from tenancy_sys;
create or replace function tenancy.event_dead_letter_record(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
    p_reason text, p_raw text) returns bigint
language plpgsql security definer set search_path = pg_catalog, tenancy, pg_temp as $$
declare
    recorded bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_topic is null or p_log_offset is null
        or p_reason is null or p_raw is null then
        raise exception 'a dead letter names its consumer, topic, offset, reason and record'
            using errcode = '22023';
    end if;
    insert into tenancy.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw)
    values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid), p_reason, p_raw)
    returning id into recorded;
    return recorded;
end
$$;
grant create on schema tenancy to tenancy_sys;
alter function tenancy.event_dead_letter_record(text, text, bigint, uuid, text, text) owner to tenancy_sys;
revoke all on function tenancy.event_dead_letter_record(text, text, bigint, uuid, text, text) from public;
grant execute on function tenancy.event_dead_letter_record(text, text, bigint, uuid, text, text) to tenancy_runtime;
revoke create on schema tenancy from tenancy_sys;
create or replace function tenancy.write_installation_setting(p_vp_key text, p_option_keys text[], p_option_values jsonb,
    p_effective_from date, p_reason text, p_audit jsonb, p_policy_changed jsonb)
    returns table (setting_id uuid, stored_at timestamptz)
language plpgsql security definer set search_path = pg_catalog, tenancy, pg_temp as $$
declare
    v_actor text := (select tenancy.bound_actor());
    v_mode constant text := tenancy.config_installation_mode();
    v_subject text;
    v_value jsonb;
    v_row record;
    v_data jsonb;
    v_id uuid := gen_random_uuid();
    v_now timestamptz;
begin
    if v_actor is null or v_actor ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]' then
        raise exception 'the bound actor is not set: an installation value records its actor' using errcode = '42501';
    end if;
    if (select tenancy.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'an installation value is written under the platform tenant only' using errcode = '42501';
    end if;
    if p_vp_key is null or p_option_keys is null or p_effective_from is null or p_reason is null
        or p_reason ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' or p_audit is null then
        raise exception 'the key, options, effective date, reason and audit row are required'
            using errcode = '22023';
    end if;
    if p_option_values is not null and (jsonb_typeof(p_option_values) <> 'object' or exists (
        select 1 from jsonb_object_keys(p_option_values) k where not (k = any (p_option_keys)))) then
        raise exception 'option values name an option that is not selected' using errcode = '22023';
    end if;
    if v_mode = 'OUTBOX' and p_policy_changed is null then
        raise exception 'this service writes PolicyChanged with every installation value' using errcode = '22023';
    end if;
    if v_mode = 'NO_OUTBOX' and p_policy_changed is not null then
        raise exception 'this service has no outbox: no PolicyChanged row is written' using errcode = '22023';
    end if;
    v_subject := 'config:' || p_vp_key || ':INSTALLATION';
    select coalesce(jsonb_agg(case when p_option_values ? o.k
            then jsonb_build_object('key', o.k, 'value', p_option_values -> o.k)
            else jsonb_build_object('key', o.k) end order by o.n), '[]'::jsonb)
        into v_value from unnest(p_option_keys) with ordinality as o(k, n);

    for v_row in
        select r.kind, r.staged, r.type, r.topic from (values
            ('audit entry', p_audit, 'com.dynokore.gst.platform.AuditEntryRecorded.v1', null::text),
            ('PolicyChanged', p_policy_changed, 'com.dynokore.gst.platform.PolicyChanged.v1', 'gst.platform.policy-changed.v1')
        ) as r(kind, staged, type, topic) where r.staged is not null
    loop
        if jsonb_typeof(v_row.staged) <> 'object'
            or not (v_row.staged ?& array['id', 'type', 'source', 'subject', 'tenant_id', 'entity_ref',
                'instance_id', 'correlation_id', 'causation_id', 'data'])
            or (v_row.staged - array['id', 'type', 'source', 'subject', 'tenant_id', 'entity_ref',
                'instance_id', 'correlation_id', 'causation_id', 'data', 'topic']) <> '{}'::jsonb
            or (v_row.topic is null and v_row.staged ? 'topic') then
            raise exception 'the staged % row has another shape', v_row.kind using errcode = '22023';
        end if;
        if v_row.staged ->> 'type' is distinct from v_row.type then
            raise exception 'the staged % row has type %', v_row.kind, v_row.staged ->> 'type'
                using errcode = '22023';
        end if;
        if v_row.topic is not null and v_row.staged ->> 'topic' is distinct from v_row.topic then
            raise exception 'the staged % row has topic %', v_row.kind, v_row.staged ->> 'topic'
                using errcode = '22023';
        end if;
        if v_row.staged ->> 'tenant_id' is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'
            or v_row.staged ->> 'entity_ref' is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff' then
            raise exception 'the staged % row is not under the platform tenant and entity', v_row.kind
                using errcode = '22023';
        end if;
        if jsonb_typeof(v_row.staged -> 'id') is distinct from 'string' or v_row.staged ->> 'id'
            !~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' then
            raise exception 'the staged % row id is not a version-7 UUID', v_row.kind using errcode = '22023';
        end if;
        if jsonb_typeof(v_row.staged -> 'source') is distinct from 'string'
            or v_row.staged ->> 'source' !~ '^/gst/[a-z][a-z-]*$' then
            raise exception 'the staged % row source is not /gst/<service>', v_row.kind using errcode = '22023';
        end if;
        if v_row.staged ->> 'subject' is distinct from v_subject then
            raise exception 'the staged % row subject is not %', v_row.kind, v_subject using errcode = '22023';
        end if;
        if jsonb_typeof(v_row.staged -> 'instance_id') is distinct from 'string'
            or jsonb_typeof(v_row.staged -> 'correlation_id') is distinct from 'string'
            or jsonb_typeof(v_row.staged -> 'causation_id') is distinct from 'string'
            or (v_row.staged ->> 'instance_id') ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
            or (v_row.staged ->> 'correlation_id') ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
            or (v_row.staged ->> 'causation_id') ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' then
            raise exception 'the staged % row needs an instance, correlation and causation', v_row.kind
                using errcode = '22023';
        end if;
        if jsonb_typeof(v_row.staged -> 'data') is distinct from 'object' then
            raise exception 'the staged % row data is not an object', v_row.kind using errcode = '22023';
        end if;
    end loop;
    if p_policy_changed is not null and (p_policy_changed ->> 'source' is distinct from p_audit ->> 'source'
        or p_policy_changed ->> 'instance_id' is distinct from p_audit ->> 'instance_id'
        or p_policy_changed ->> 'correlation_id' is distinct from p_audit ->> 'correlation_id'
        or p_policy_changed ->> 'causation_id' is distinct from p_audit ->> 'causation_id') then
        raise exception 'the staged rows disagree on source, instance, correlation or causation'
            using errcode = '22023';
    end if;

    v_data := p_audit -> 'data';
    if jsonb_typeof(v_data -> 'actor') is distinct from 'string' or v_data ->> 'actor' is distinct from v_actor then
        raise exception 'the audit entry actor is not the bound actor' using errcode = '42501';
    end if;
    if jsonb_typeof(v_data -> 'priorState') is distinct from 'object' then
        raise exception 'the audit entry has no prior state object (INV-REQ-2588 b2, INV-AC-2229)'
            using errcode = '22023';
    end if;
    if (v_data - array['actor', 'action', 'scope', 'reason', 'priorState']) <> '{}'::jsonb then
        raise exception 'the audit entry payload has another shape' using errcode = '22023';
    end if;
    if v_data ->> 'action' is distinct from 'setPolicy' then
        raise exception 'the audit entry action is %', v_data ->> 'action' using errcode = '22023';
    end if;
    if v_data ->> 'scope' is distinct from 'INSTALLATION' then
        raise exception 'the audit entry scope is %', v_data ->> 'scope' using errcode = '22023';
    end if;
    if v_data ->> 'reason' is distinct from p_reason then
        raise exception 'the audit entry reason is not the value''s' using errcode = '22023';
    end if;

    if p_policy_changed is not null then
        v_data := p_policy_changed -> 'data';
        if v_data -> 'scope' is distinct from to_jsonb('INSTALLATION'::text) then
            raise exception 'the PolicyChanged scope is %', v_data -> 'scope' using errcode = '22023';
        end if;
        if v_data -> 'vpId' is distinct from to_jsonb(p_vp_key) then
            raise exception 'the PolicyChanged vpId is %', v_data -> 'vpId' using errcode = '22023';
        end if;
        if v_data -> 'value' is distinct from v_value then
            raise exception 'the PolicyChanged value is not the value written' using errcode = '22023';
        end if;
        if v_data -> 'effectiveDate' is distinct from to_jsonb(to_char(p_effective_from, 'YYYY-MM-DD')) then
            raise exception 'the PolicyChanged effectiveDate is %', v_data -> 'effectiveDate'
                using errcode = '22023';
        end if;
        if v_data is distinct from jsonb_build_object('scope', 'INSTALLATION', 'vpId', p_vp_key,
            'value', v_value, 'effectiveDate', to_char(p_effective_from, 'YYYY-MM-DD'), 'source', 'OVERRIDE') then
            raise exception 'the PolicyChanged payload has another source or other fields' using errcode = '22023';
        end if;
    end if;

    v_now := clock_timestamp();
    insert into tenancy.config_installation_setting
        (id, vp_key, option_keys, option_values, effective_from, set_by, reason, recorded_at)
    values (v_id, p_vp_key, p_option_keys, p_option_values, p_effective_from, v_actor, p_reason, v_now);
    if v_mode = 'OUTBOX' then
        insert into tenancy.outbox
            (id, topic, type, source, subject, event_time, tenant_id, entity_ref, doc_uuid, transaction_date,
             docsequence, instance_id, correlation_id, causation_id, data)
        values (p_audit ->> 'id', 'gst.platform.audit-entry-recorded.v1', p_audit ->> 'type', p_audit ->> 'source',
            p_audit ->> 'subject', v_now, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'ffffffff-ffff-ffff-ffff-ffffffffffff', null, null, null,
            p_audit ->> 'instance_id', p_audit ->> 'correlation_id', p_audit ->> 'causation_id',
            p_audit -> 'data');
        insert into tenancy.outbox
            (id, topic, type, source, subject, event_time, tenant_id, entity_ref, doc_uuid, transaction_date,
             docsequence, instance_id, correlation_id, causation_id, data)
        values (p_policy_changed ->> 'id', 'gst.platform.policy-changed.v1', p_policy_changed ->> 'type',
            p_policy_changed ->> 'source', p_policy_changed ->> 'subject', v_now, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid,
            'ffffffff-ffff-ffff-ffff-ffffffffffff', null, null, null, p_policy_changed ->> 'instance_id',
            p_policy_changed ->> 'correlation_id', p_policy_changed ->> 'causation_id',
            p_policy_changed -> 'data');
    else
        insert into tenancy.audit_entry
            (id, tenant_id, entity_ref, recorded_at, source, subject, doc_uuid, transaction_date,
             instance_id, correlation_id, causation_id, actor, action, scope, reason, prior_state)
        values ((p_audit ->> 'id')::uuid, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'ffffffff-ffff-ffff-ffff-ffffffffffff', v_now, p_audit ->> 'source',
            p_audit ->> 'subject', null, null, p_audit ->> 'instance_id', p_audit ->> 'correlation_id',
            p_audit ->> 'causation_id', v_actor, p_audit -> 'data' ->> 'action',
            p_audit -> 'data' ->> 'scope', p_audit -> 'data' ->> 'reason', p_audit -> 'data' -> 'priorState');
    end if;
    return query select v_id, v_now;
end
$$;
grant create on schema tenancy to tenancy_sys;
alter function tenancy.write_installation_setting(text, text[], jsonb, date, text, jsonb, jsonb) owner to tenancy_sys;
revoke all on function tenancy.write_installation_setting(text, text[], jsonb, date, text, jsonb, jsonb) from public;
grant execute on function tenancy.write_installation_setting(text, text[], jsonb, date, text, jsonb, jsonb) to tenancy_runtime;
revoke create on schema tenancy from tenancy_sys;
create or replace function tenancy.config_purge_subject(p_subject_type text, p_subject_ref text) returns bigint
language plpgsql security definer set search_path = pg_catalog, tenancy, pg_temp as $$
declare
    v_actor text := (select tenancy.bound_actor());
    v_tenant uuid := (select tenancy.bound_tenant());
    v_deleted bigint;
begin
    if v_actor is null or v_actor ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]' then
        raise exception 'the bound actor is not set: a purge records its actor' using errcode = '42501';
    end if;
    if v_tenant is null or v_tenant = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'a purge runs in a transaction of the tenant concerned' using errcode = '42501';
    end if;
    if p_subject_type is null or p_subject_ref is null then
        raise exception 'the subject is required' using errcode = '22023';
    end if;
    delete from tenancy.config_resolved_value
    where tenant_id = v_tenant and subject_type = p_subject_type and subject_ref = p_subject_ref;
    get diagnostics v_deleted = row_count;
    return v_deleted;
end
$$;
grant create on schema tenancy to tenancy_sys;
alter function tenancy.config_purge_subject(text, text) owner to tenancy_sys;
revoke all on function tenancy.config_purge_subject(text, text) from public;
grant execute on function tenancy.config_purge_subject(text, text) to tenancy_runtime;
revoke create on schema tenancy from tenancy_sys;
create or replace function tenancy.config_purge_tenant(p_tenant uuid)
    returns table (resolved_values bigint, settings bigint, relaxation_acknowledgements bigint)
language plpgsql security definer set search_path = pg_catalog, tenancy, pg_temp as $$
declare
    v_actor text := (select tenancy.bound_actor());
    v_tenant uuid := (select tenancy.bound_tenant());
    v_resolved bigint;
    v_settings bigint;
    v_acknowledgements bigint;
begin
    if v_actor is null or v_actor ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]' then
        raise exception 'the bound actor is not set: a purge records its actor' using errcode = '42501';
    end if;
    if v_tenant is null or v_tenant = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid or p_tenant is distinct from v_tenant then
        raise exception 'a tenant is purged only in its own transaction' using errcode = '42501';
    end if;
    delete from tenancy.config_resolved_value where tenant_id = v_tenant;
    get diagnostics v_resolved = row_count;
    delete from tenancy.config_setting where tenant_id = v_tenant;
    get diagnostics v_settings = row_count;
    delete from tenancy.config_relaxation_acknowledgement where tenant_id = v_tenant;
    get diagnostics v_acknowledgements = row_count;
    return query select v_resolved, v_settings, v_acknowledgements;
end
$$;
grant create on schema tenancy to tenancy_sys;
alter function tenancy.config_purge_tenant(uuid) owner to tenancy_sys;
revoke all on function tenancy.config_purge_tenant(uuid) from public;
grant execute on function tenancy.config_purge_tenant(uuid) to tenancy_runtime;
revoke create on schema tenancy from tenancy_sys;
create or replace function tenancy.config_option_value_maximum(p_vp_key text, p_option_key text) returns numeric
language plpgsql stable security definer set search_path = pg_catalog, tenancy, pg_temp as $$
declare
    v_tenant uuid := (select tenancy.bound_tenant());
begin
    if v_tenant is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'the installation-wide maximum is read in a platform transaction'
            using errcode = '42501';
    end if;
    if p_vp_key is null or p_vp_key !~ '^VP-[0-9]{2,3}$' then
        raise exception 'the variation point key is required' using errcode = '22023';
    end if;
    if p_option_key is null or p_option_key ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' then
        raise exception 'the option key is required' using errcode = '22023';
    end if;
    return (
        with scoped as (
            select tenant_id, scope_level, scope_id, source, option_keys, option_values, effective_from, seq,
                bool_or(source = 'OVERRIDE') over (partition by tenant_id, scope_level, scope_id)
                    as has_override
            from tenancy.config_setting
            where vp_key = p_vp_key
        ), chosen as (
            select * from scoped where source = 'OVERRIDE' or not has_override
        ), ranked as (
            select *,
                row_number() over (
                    partition by tenant_id, scope_level, scope_id, effective_from <= current_date
                    order by effective_from desc, seq desc) as rank_in_force,
                row_number() over (
                    partition by tenant_id, scope_level, scope_id, effective_from
                    order by seq desc) as rank_on_date
            from chosen
        ), counted as (
            select option_values -> p_option_key as member
            from ranked
            where p_option_key = any (option_keys)
                and case when effective_from <= current_date then rank_in_force else rank_on_date end = 1
        )
        select max(
            case jsonb_typeof(member)
                when 'number' then (member #>> '{}')::numeric
                when 'object' then (
                    select max((e.value #>> '{}')::numeric)
                    from jsonb_each(member) as e
                    where jsonb_typeof(e.value) = 'number')
            end)
        from counted
    );
end
$$;
grant create on schema tenancy to tenancy_sys;
alter function tenancy.config_option_value_maximum(text, text) owner to tenancy_sys;
revoke all on function tenancy.config_option_value_maximum(text, text) from public;
grant execute on function tenancy.config_option_value_maximum(text, text) to tenancy_runtime;
revoke create on schema tenancy from tenancy_sys;
alter table tenancy.config_setting drop constraint config_setting_scope_id_check, add constraint config_setting_scope_id_check check (scope_id !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$') not valid;
alter table tenancy.config_setting validate constraint config_setting_scope_id_check;
alter table tenancy.config_setting drop constraint config_setting_set_by_check, add constraint config_setting_set_by_check check (set_by !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]') not valid;
alter table tenancy.config_setting validate constraint config_setting_set_by_check;
alter table tenancy.config_setting drop constraint config_setting_reason_check, add constraint config_setting_reason_check check (reason !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$') not valid;
alter table tenancy.config_setting validate constraint config_setting_reason_check;
alter table tenancy.config_installation_setting drop constraint config_installation_setting_set_by_check, add constraint config_installation_setting_set_by_check check (set_by !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]') not valid;
alter table tenancy.config_installation_setting validate constraint config_installation_setting_set_by_check;
alter table tenancy.config_installation_setting drop constraint config_installation_setting_reason_check, add constraint config_installation_setting_reason_check check (reason !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$') not valid;
alter table tenancy.config_installation_setting validate constraint config_installation_setting_reason_check;
alter table tenancy.config_resolved_value drop constraint config_resolved_value_subject_ref_check, add constraint config_resolved_value_subject_ref_check check (subject_ref !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$') not valid;
alter table tenancy.config_resolved_value validate constraint config_resolved_value_subject_ref_check;
alter table tenancy.config_relaxation_acknowledgement drop constraint config_relaxation_acknowledgement_scope_id_check, add constraint config_relaxation_acknowledgement_scope_id_check check (scope_id !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$') not valid;
alter table tenancy.config_relaxation_acknowledgement validate constraint config_relaxation_acknowledgement_scope_id_check;
alter table tenancy.config_relaxation_acknowledgement drop constraint config_relaxation_acknowledgement_role_check, add constraint config_relaxation_acknowledgement_role_check check (role !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$') not valid;
alter table tenancy.config_relaxation_acknowledgement validate constraint config_relaxation_acknowledgement_role_check;
alter table tenancy.config_relaxation_acknowledgement drop constraint config_relaxation_acknowledgement_principal_check, add constraint config_relaxation_acknowledgement_principal_check check (principal !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]') not valid;
alter table tenancy.config_relaxation_acknowledgement validate constraint config_relaxation_acknowledgement_principal_check;
alter table tenancy.config_relaxation_acknowledgement drop constraint config_relaxation_acknowledgement_reason_check, add constraint config_relaxation_acknowledgement_reason_check check (reason is null or reason !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$') not valid;
alter table tenancy.config_relaxation_acknowledgement validate constraint config_relaxation_acknowledgement_reason_check;
