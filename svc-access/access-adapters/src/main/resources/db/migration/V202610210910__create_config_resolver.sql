-- V202610210910__create_config_resolver.sql (INV-T-4935; TPL-12, INV-REQ-2756)
-- Generated: config-resolver's ConfigSchema.ddl("access", "access_runtime", "access_sys", OUTBOX) of the BOM 0.103.0 train,
-- for the tenant-scope role-precedence policy VP-73 that setPolicy of service-runtime stores. GeneratedMigrationsTest
-- fails when this file differs from what the pinned module generates. Run only by the migration Job as access_migrate.

create table access.config_setting (
    seq bigint generated always as identity primary key,
    id uuid not null unique,
    tenant_id uuid not null,
    entity_id text,
    vp_key text not null check (vp_key ~ '^VP-[0-9]{2,3}$'),
    scope_level text not null check (scope_level in ('TENANT', 'GROUP', 'ENTITY', 'FEED', 'AP_CONNECTION', 'CLIENT_ENTITY')),
    scope_id text not null check (scope_id !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'),
    option_keys text[] not null check (cardinality(option_keys) >= 1 and array_position(option_keys, null) is null),
    option_values jsonb check (option_values is null or jsonb_typeof(option_values) = 'object'),
    source text not null check (source in ('OVERRIDE', 'PROFILE')),
    profile_id text,
    profile_version text,
    effective_from date not null,
    set_by text not null check (set_by !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$|[\u0001-\u001F\u007F-\u009F]'),
    reason text not null check (reason !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'),
    recorded_at timestamptz not null,
    relaxation_ref uuid,
    check ((scope_level in ('TENANT', 'GROUP')) = (entity_id is null)),
    check ((source = 'PROFILE') = (profile_id is not null and profile_version is not null))
);
create index config_setting_lookup on access.config_setting
    (tenant_id, vp_key, scope_level, scope_id, source, effective_from desc, seq desc);
create unique index config_setting_relaxation_ref on access.config_setting (tenant_id, relaxation_ref)
    where relaxation_ref is not null;
comment on column access.config_setting.reason is 'class: content';
create table access.config_installation_setting (
    seq bigint generated always as identity primary key,
    id uuid not null unique,
    vp_key text not null check (vp_key ~ '^VP-[0-9]{2,3}$'),
    option_keys text[] not null check (cardinality(option_keys) >= 1 and array_position(option_keys, null) is null),
    option_values jsonb check (option_values is null or jsonb_typeof(option_values) = 'object'),
    effective_from date not null,
    set_by text not null check (set_by !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$|[\u0001-\u001F\u007F-\u009F]'),
    reason text not null check (reason !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'),
    recorded_at timestamptz not null
);
create index config_installation_setting_lookup on access.config_installation_setting
    (vp_key, effective_from desc, seq desc);
comment on column access.config_installation_setting.reason is 'class: content';
comment on table access.config_installation_setting is 'platform-scoped: no tenant data';
create table access.config_resolved_value (
    id uuid primary key,
    tenant_id uuid not null,
    entity_id text,
    subject_type text not null check (subject_type ~ '^[a-z][a-z0-9_]{0,62}$'),
    subject_ref text not null check (subject_ref !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'),
    vp_key text not null check (vp_key ~ '^VP-[0-9]{2,3}$'),
    transaction_date date not null,
    option_keys text[] not null check (cardinality(option_keys) >= 1 and array_position(option_keys, null) is null),
    option_values jsonb check (option_values is null or jsonb_typeof(option_values) = 'object'),
    source text not null check (source in ('OVERRIDE', 'PROFILE', 'DEFAULT')),
    source_level text check (source_level in ('INSTALLATION', 'TENANT', 'GROUP', 'ENTITY', 'FEED', 'AP_CONNECTION', 'CLIENT_ENTITY')),
    source_scope_id text,
    source_setting_id uuid,
    profile_id text,
    profile_version text,
    resolved_at timestamptz not null,
    capped_by jsonb
        check (capped_by is null or (jsonb_typeof(capped_by) = 'array' and jsonb_array_length(capped_by) > 0)),
    unique (tenant_id, subject_type, subject_ref, vp_key),
    check ((source = 'DEFAULT') = (source_level is null)),
    check ((source = 'DEFAULT') = (source_scope_id is null)),
    check ((source = 'DEFAULT') = (source_setting_id is null)),
    check ((source = 'PROFILE') = (profile_id is not null and profile_version is not null))
);
create table access.config_relaxation_acknowledgement (
    seq bigint generated always as identity primary key,
    id uuid not null unique,
    relaxation_id uuid not null,
    tenant_id uuid not null,
    entity_id text,
    vp_key text not null check (vp_key ~ '^VP-[0-9]{2,3}$'),
    scope_level text not null check (scope_level in ('TENANT', 'GROUP', 'ENTITY', 'FEED', 'AP_CONNECTION', 'CLIENT_ENTITY')),
    scope_id text not null check (scope_id !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'),
    option_keys text[] not null check (cardinality(option_keys) >= 1 and array_position(option_keys, null) is null),
    option_values jsonb check (option_values is null or jsonb_typeof(option_values) = 'object'),
    role text not null check (role !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'),
    principal text not null check (principal !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$|[\u0001-\u001F\u007F-\u009F]'),
    effective_from date not null,
    reason text check (reason is null or reason !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'),
    acknowledged_at timestamptz not null,
    unique (tenant_id, relaxation_id, role),
    unique (tenant_id, relaxation_id, principal),
    check ((scope_level in ('TENANT', 'GROUP')) = (entity_id is null))
);
create index config_relaxation_acknowledgement_scope on access.config_relaxation_acknowledgement
    (tenant_id, vp_key, scope_level, scope_id);
comment on column access.config_relaxation_acknowledgement.principal is 'class: personal';
comment on column access.config_relaxation_acknowledgement.reason is 'class: content';
ALTER TABLE access.config_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.config_setting FORCE ROW LEVEL SECURITY;
CREATE POLICY config_setting_tenant_isolation ON access.config_setting
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));
ALTER TABLE access.config_resolved_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.config_resolved_value FORCE ROW LEVEL SECURITY;
CREATE POLICY config_resolved_value_tenant_isolation ON access.config_resolved_value
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));
ALTER TABLE access.config_relaxation_acknowledgement ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.config_relaxation_acknowledgement FORCE ROW LEVEL SECURITY;
CREATE POLICY config_relaxation_acknowledgement_tenant_isolation ON access.config_relaxation_acknowledgement
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));
grant usage on schema access to access_runtime, access_sys;
grant select, insert on access.config_setting, access.config_resolved_value, access.config_relaxation_acknowledgement to access_runtime;
grant select on access.config_installation_setting to access_runtime;
grant insert on access.config_installation_setting to access_sys;
grant select (tenant_id, subject_type, subject_ref) on access.config_resolved_value to access_sys;
grant select (tenant_id) on access.config_relaxation_acknowledgement to access_sys;
grant select (tenant_id, vp_key, scope_level, scope_id, source, option_keys, option_values, effective_from, seq)
    on access.config_setting to access_sys;
create policy config_setting_sys_select on access.config_setting for select to access_sys using (true);
grant delete on access.config_setting, access.config_resolved_value, access.config_relaxation_acknowledgement to access_sys;
create function access.config_installation_mode() returns text
language sql immutable set search_path = pg_catalog, access, pg_temp as $$ select 'OUTBOX' $$;
grant create on schema access to access_sys;
alter function access.config_installation_mode() owner to access_sys;
revoke all on function access.config_installation_mode() from public;
revoke create on schema access from access_sys;
create function access.write_installation_setting(p_vp_key text, p_option_keys text[], p_option_values jsonb,
    p_effective_from date, p_reason text, p_audit jsonb, p_policy_changed jsonb)
    returns table (setting_id uuid, stored_at timestamptz)
language plpgsql security definer set search_path = pg_catalog, access, pg_temp as $$
declare
    v_actor text := (select access.bound_actor());
    v_mode constant text := access.config_installation_mode();
    v_subject text;
    v_value jsonb;
    v_row record;
    v_data jsonb;
    v_id uuid := gen_random_uuid();
    v_now timestamptz;
begin
    if v_actor is null or v_actor ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$|[\u0001-\u001F\u007F-\u009F]' then
        raise exception 'the bound actor is not set: an installation value records its actor' using errcode = '42501';
    end if;
    if (select access.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'an installation value is written under the platform tenant only' using errcode = '42501';
    end if;
    if p_vp_key is null or p_option_keys is null or p_effective_from is null or p_reason is null
        or p_reason ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$' or p_audit is null then
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
            or (v_row.staged ->> 'instance_id') ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
            or (v_row.staged ->> 'correlation_id') ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'
            or (v_row.staged ->> 'causation_id') ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$' then
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
    insert into access.config_installation_setting
        (id, vp_key, option_keys, option_values, effective_from, set_by, reason, recorded_at)
    values (v_id, p_vp_key, p_option_keys, p_option_values, p_effective_from, v_actor, p_reason, v_now);
    if v_mode = 'OUTBOX' then
        insert into access.outbox
            (id, topic, type, source, subject, event_time, tenant_id, entity_ref, doc_uuid, transaction_date,
             docsequence, instance_id, correlation_id, causation_id, data)
        values (p_audit ->> 'id', 'gst.platform.audit-entry-recorded.v1', p_audit ->> 'type', p_audit ->> 'source',
            p_audit ->> 'subject', v_now, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'ffffffff-ffff-ffff-ffff-ffffffffffff', null, null, null,
            p_audit ->> 'instance_id', p_audit ->> 'correlation_id', p_audit ->> 'causation_id',
            p_audit -> 'data');
        insert into access.outbox
            (id, topic, type, source, subject, event_time, tenant_id, entity_ref, doc_uuid, transaction_date,
             docsequence, instance_id, correlation_id, causation_id, data)
        values (p_policy_changed ->> 'id', 'gst.platform.policy-changed.v1', p_policy_changed ->> 'type',
            p_policy_changed ->> 'source', p_policy_changed ->> 'subject', v_now, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid,
            'ffffffff-ffff-ffff-ffff-ffffffffffff', null, null, null, p_policy_changed ->> 'instance_id',
            p_policy_changed ->> 'correlation_id', p_policy_changed ->> 'causation_id',
            p_policy_changed -> 'data');
    else
        insert into access.audit_entry
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
grant create on schema access to access_sys;
alter function access.write_installation_setting(text, text[], jsonb, date, text, jsonb, jsonb) owner to access_sys;
revoke all on function access.write_installation_setting(text, text[], jsonb, date, text, jsonb, jsonb) from public;
grant execute on function access.write_installation_setting(text, text[], jsonb, date, text, jsonb, jsonb) to access_runtime;
revoke create on schema access from access_sys;
create function access.config_purge_subject(p_subject_type text, p_subject_ref text) returns bigint
language plpgsql security definer set search_path = pg_catalog, access, pg_temp as $$
declare
    v_actor text := (select access.bound_actor());
    v_tenant uuid := (select access.bound_tenant());
    v_deleted bigint;
begin
    if v_actor is null or v_actor ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$|[\u0001-\u001F\u007F-\u009F]' then
        raise exception 'the bound actor is not set: a purge records its actor' using errcode = '42501';
    end if;
    if v_tenant is null or v_tenant = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'a purge runs in a transaction of the tenant concerned' using errcode = '42501';
    end if;
    if p_subject_type is null or p_subject_ref is null then
        raise exception 'the subject is required' using errcode = '22023';
    end if;
    delete from access.config_resolved_value
    where tenant_id = v_tenant and subject_type = p_subject_type and subject_ref = p_subject_ref;
    get diagnostics v_deleted = row_count;
    return v_deleted;
end
$$;
grant create on schema access to access_sys;
alter function access.config_purge_subject(text, text) owner to access_sys;
revoke all on function access.config_purge_subject(text, text) from public;
grant execute on function access.config_purge_subject(text, text) to access_runtime;
revoke create on schema access from access_sys;
create function access.config_purge_tenant(p_tenant uuid)
    returns table (resolved_values bigint, settings bigint, relaxation_acknowledgements bigint)
language plpgsql security definer set search_path = pg_catalog, access, pg_temp as $$
declare
    v_actor text := (select access.bound_actor());
    v_tenant uuid := (select access.bound_tenant());
    v_resolved bigint;
    v_settings bigint;
    v_acknowledgements bigint;
begin
    if v_actor is null or v_actor ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$|[\u0001-\u001F\u007F-\u009F]' then
        raise exception 'the bound actor is not set: a purge records its actor' using errcode = '42501';
    end if;
    if v_tenant is null or v_tenant = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid or p_tenant is distinct from v_tenant then
        raise exception 'a tenant is purged only in its own transaction' using errcode = '42501';
    end if;
    delete from access.config_resolved_value where tenant_id = v_tenant;
    get diagnostics v_resolved = row_count;
    delete from access.config_setting where tenant_id = v_tenant;
    get diagnostics v_settings = row_count;
    delete from access.config_relaxation_acknowledgement where tenant_id = v_tenant;
    get diagnostics v_acknowledgements = row_count;
    return query select v_resolved, v_settings, v_acknowledgements;
end
$$;
grant create on schema access to access_sys;
alter function access.config_purge_tenant(uuid) owner to access_sys;
revoke all on function access.config_purge_tenant(uuid) from public;
grant execute on function access.config_purge_tenant(uuid) to access_runtime;
revoke create on schema access from access_sys;
create function access.config_option_value_maximum(p_vp_key text, p_option_key text) returns numeric
language plpgsql stable security definer set search_path = pg_catalog, access, pg_temp as $$
declare
    v_tenant uuid := (select access.bound_tenant());
begin
    if v_tenant is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'the installation-wide maximum is read in a platform transaction'
            using errcode = '42501';
    end if;
    if p_vp_key is null or p_vp_key !~ '^VP-[0-9]{2,3}$' then
        raise exception 'the variation point key is required' using errcode = '22023';
    end if;
    if p_option_key is null or p_option_key ~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$' then
        raise exception 'the option key is required' using errcode = '22023';
    end if;
    return (
        with scoped as (
            select tenant_id, scope_level, scope_id, source, option_keys, option_values, effective_from, seq,
                bool_or(source = 'OVERRIDE') over (partition by tenant_id, scope_level, scope_id)
                    as has_override
            from access.config_setting
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
grant create on schema access to access_sys;
alter function access.config_option_value_maximum(text, text) owner to access_sys;
revoke all on function access.config_option_value_maximum(text, text) from public;
grant execute on function access.config_option_value_maximum(text, text) to access_runtime;
revoke create on schema access from access_sys;
