-- V202611151000__pack_arming.sql (INV-T-22942, slice 2 of the arming fence; INV-REQ-952, INV-REQ-1166, INV-Q-4884):
-- the tenant's acknowledgement of its own delta, the record of an unreviewed or reviewed activation, the readiness
-- answers per instance, and the SECURITY DEFINER functions the platform-bound arming reads and writes through.

-- 1. rules.pack_delta_ack: a tenant's acknowledgement of its own delta. The acting user ID, never a role.
create table rules.pack_delta_ack (
    tenant_id       uuid        not null,
    pack_type       text        not null check (pack_type !~ '^\s*$'),
    pack_version    text        not null check (pack_version !~ '^\s*$'),
    acknowledged_by text        not null check (acknowledged_by !~ '^\s*$|[[:cntrl:]]'),
    acknowledged_at timestamptz not null default clock_timestamp(),
    primary key (tenant_id, pack_type, pack_version)
);
alter table rules.pack_delta_ack enable row level security;
alter table rules.pack_delta_ack force row level security;
create policy pack_delta_ack_tenant_read on rules.pack_delta_ack for select
    using (tenant_id = (select rules.bound_tenant()));
create policy pack_delta_ack_tenant_insert on rules.pack_delta_ack for insert
    with check (tenant_id = (select rules.bound_tenant()));
-- The tenant function below reads whether an acknowledgement exists, as rules_sys and nothing else.
create policy pack_delta_ack_sys_read on rules.pack_delta_ack for select to rules_sys using (true);
-- Written once and never changed: insert and select only (NIST SP 800-53 AU-10).
revoke all on rules.pack_delta_ack from public;
grant select, insert on rules.pack_delta_ack to rules_runtime;
grant select on rules.pack_delta_ack to rules_sys;

-- 2. rules.pack_activation_review: the record per tenant that an activation was reviewed or proceeded unreviewed
-- (INV-REQ-952). A tenant reads its own rows; the rows are written by rules.arm_platform_pack only.
create table rules.pack_activation_review (
    tenant_id    uuid    not null,
    pack_type    text    not null check (pack_type !~ '^\s*$'),
    pack_version text    not null check (pack_version !~ '^\s*$'),
    reviewed     boolean not null,
    decided_on   date    not null,
    primary key (tenant_id, pack_type, pack_version)
);
alter table rules.pack_activation_review enable row level security;
alter table rules.pack_activation_review force row level security;
create policy pack_activation_review_tenant_read on rules.pack_activation_review for select
    using (tenant_id = (select rules.bound_tenant()));
create policy pack_activation_review_sys_insert on rules.pack_activation_review for insert to rules_sys
    with check (true);
revoke all on rules.pack_activation_review from public;
grant select on rules.pack_activation_review to rules_runtime;
grant insert on rules.pack_activation_review to rules_sys;

-- 3. rules.pack_readiness_ack: the latest readiness answer of each instance for a pack version. A platform table: no
-- tenant column, no row-level policy; the runtime login reads and writes it, nobody else.
create table rules.pack_readiness_ack (
    pack_type      text        not null check (pack_type !~ '^\s*$'),
    pack_version   text        not null check (pack_version !~ '^\s*$'),
    instance       text        not null check (instance !~ '^\s*$'),
    outcome        text        not null check (outcome in ('LOADED', 'FAILED')),
    failure_reason text        null,
    received_at    timestamptz not null,
    primary key (pack_type, pack_version, instance),
    check (outcome = 'FAILED' or failure_reason is null)
);
comment on table rules.pack_readiness_ack is 'platform-scoped: no tenant data';
revoke all on rules.pack_readiness_ack from public;
grant select, insert, update on rules.pack_readiness_ack to rules_runtime;

-- 4. The platform binding reads no tenant row through the row-level policies, so the two functions below run as
-- rules_sys, which is admitted to the rows they read and write by policies of its own.
create policy dry_run_delta_sys_read on rules.dry_run_delta for select to rules_sys using (true);
grant select on rules.dry_run_delta to rules_sys;

-- The tenants that hold a COMPLETE delta for a pack type and version: the tenant ID, the completed_at of its latest
-- COMPLETE row and whether it acknowledged; no other column leaves the function. Platform binding only. The tenant
-- column is not named tenant_id, so that jOOQ's tenancy layer does not filter the platform-bound listing by the bound
-- tenant: the function is declared platform-scoped and reads the signed binding itself.
create function rules.tenants_with_delta(p_pack_type text, p_pack_version text)
    returns table (delta_tenant_id uuid, completed_at timestamptz, acknowledged boolean)
language plpgsql stable security definer set search_path = pg_catalog, rules, pg_temp as $$
begin
    if (select rules.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'the tenants of a delta are listed under the platform tenant only' using errcode = '42501';
    end if;
    return query
        select d.tenant_id as delta_tenant_id, max(d.completed_at),
               exists (select 1 from rules.pack_delta_ack a
                       where a.tenant_id = d.tenant_id and a.pack_type = p_pack_type and a.pack_version = p_pack_version)
            from rules.dry_run_delta d
            where d.pack_type = p_pack_type and d.pack_version = p_pack_version and d.state = 'COMPLETE'
            group by d.tenant_id
            order by d.tenant_id;
end
$$;

-- The arming write: the review rows and the registry status ARMED in one statement list, so a failure of either leaves
-- neither. The transition rules stay in the frozen-update trigger. Platform binding only.
create function rules.arm_platform_pack(p_pack_type text, p_pack_version text, p_tenant_ids uuid[],
    p_reviewed boolean[], p_decided_on date[])
    returns void
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
declare
    v_actor text := (select rules.bound_actor());
begin
    if (select rules.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'a platform pack is armed under the platform tenant only' using errcode = '42501';
    end if;
    if v_actor is null or v_actor ~ '^\s*$|[[:cntrl:]]' then
        raise exception 'the bound actor is not set: arming a platform pack records its actor' using errcode = '42501';
    end if;
    if p_tenant_ids is null or p_reviewed is null or p_decided_on is null
        or cardinality(p_tenant_ids) <> cardinality(p_reviewed) or cardinality(p_tenant_ids) <> cardinality(p_decided_on)
        or array_position(p_tenant_ids, null) is not null or array_position(p_reviewed, null) is not null
        or array_position(p_decided_on, null) is not null then
        raise exception 'the review rows are a list of tenants with one reviewed flag and one date each'
            using errcode = '22023';
    end if;
    update rules.pack_registry set status = 'ARMED'
        where pack_type = p_pack_type and pack_version = p_pack_version
            and tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid and status in ('APPROVED', 'ACKNOWLEDGED');
    if not found then
        raise exception 'no APPROVED or ACKNOWLEDGED platform pack version to arm' using errcode = '02000';
    end if;
    insert into rules.pack_activation_review (tenant_id, pack_type, pack_version, reviewed, decided_on)
        select t.tenant_id, p_pack_type, p_pack_version, t.reviewed, t.decided_on
            from unnest(p_tenant_ids, p_reviewed, p_decided_on) as t(tenant_id, reviewed, decided_on);
end
$$;

grant create on schema rules to rules_sys;
alter function rules.tenants_with_delta(text, text) owner to rules_sys;
alter function rules.arm_platform_pack(text, text, uuid[], boolean[], date[]) owner to rules_sys;
revoke create on schema rules from rules_sys;
revoke execute on function rules.tenants_with_delta(text, text) from public;
revoke execute on function rules.arm_platform_pack(text, text, uuid[], boolean[], date[]) from public;
grant execute on function rules.tenants_with_delta(text, text) to rules_runtime;
grant execute on function rules.arm_platform_pack(text, text, uuid[], boolean[], date[]) to rules_runtime;
