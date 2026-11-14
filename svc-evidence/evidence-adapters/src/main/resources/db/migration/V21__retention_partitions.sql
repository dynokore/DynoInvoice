-- V21__retention_partitions.sql (INV-T-22969; INV-REQ-639, INV-AC-22962; owner answer 22842 A, 2026-11-15).
--
-- The storage side of retention-end deletion. The four chain tables become declarative range partitions by
-- (tenant_id, entity_ref, period_ref); evidence.retention_partition is the registry and the only map from a
-- partitionId to its tenant, entity and period. A partition is one (tenant, entity, period); its range is
-- [period, period || U+0001) on the "C" collation, so no other period or tenant can land in it. Existing rows are
-- copied into partitions created for their (tenant, entity, period) before the old tables are dropped.
--
-- Append path: an append for a (tenant, entity, period) with no partition calls evidence.ensure_partition first
-- (ChainWiring, EnsuringLedger). A DEFAULT partition per chain table is the safety net for a direct insert of the
-- unchanged library; it belongs to no registry row, so no deletion function ever detaches it.
--
-- Deletion: detach_expired_partition, mark_partition_archived and drop_detached_partition are SECURITY DEFINER with a
-- fixed search_path, owned by the table owner and executable only by the runtime login. The runtime login keeps no
-- DELETE, TRUNCATE or DDL right on any of these tables (NIST SP 800-53 AU-9, AC-5, AC-6; OWASP ASVS V1, V4.1.1).
-- Chain verification (DEP-007) is read from evidence.integrity_failure (open as long as a row exists, V7) and from
-- the latest evidence.integrity_run.

-- The owner-owned functions below evaluate the signed binding themselves, as the sys-owned functions of V7 and V10 do.
grant execute on function evidence.bound_tenant() to evidence_migrate;

create table evidence.retention_partition (
    partition_id text primary key check (partition_id ~ '^rp_[0-9a-f]{32}$'),
    tenant_id uuid not null,
    entity_ref text not null,
    period_from text not null,
    period_to text not null,
    state text not null check (state in ('ATTACHED', 'DETACHED', 'ARCHIVED', 'DROPPED')),
    tail_hashes jsonb,
    retention_end timestamptz,
    archive_ref text,
    detached_at timestamptz,
    archived_at timestamptz,
    dropped_at timestamptz,
    unique (tenant_id, entity_ref, period_from, period_to)
);

create temp table mig_decision_trace on commit drop as select * from evidence.decision_trace;
drop table evidence.decision_trace;
create table evidence.decision_trace (id uuid not null, tenant_id uuid not null, entity_ref text collate "C" not null, period_ref text collate "C" not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, version integer check (version is null or version >= 1), primary key (tenant_id, entity_ref, period_ref, id), unique (tenant_id, entity_ref, period_ref, seq)) partition by range (tenant_id, entity_ref, period_ref);
create table evidence.decision_trace_default partition of evidence.decision_trace default;

create temp table mig_evidence_payload on commit drop as select * from evidence.evidence_payload;
drop table evidence.evidence_payload;
create table evidence.evidence_payload (id uuid not null, tenant_id uuid not null, entity_ref text collate "C" not null, period_ref text collate "C" not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, primary key (tenant_id, entity_ref, period_ref, id), unique (tenant_id, entity_ref, period_ref, seq)) partition by range (tenant_id, entity_ref, period_ref);
create table evidence.evidence_payload_default partition of evidence.evidence_payload default;

create temp table mig_acknowledgement on commit drop as select * from evidence.acknowledgement;
drop table evidence.acknowledgement;
create table evidence.acknowledgement (id uuid not null, tenant_id uuid not null, entity_ref text collate "C" not null, period_ref text collate "C" not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, primary key (tenant_id, entity_ref, period_ref, id), unique (tenant_id, entity_ref, period_ref, seq)) partition by range (tenant_id, entity_ref, period_ref);
create table evidence.acknowledgement_default partition of evidence.acknowledgement default;

create temp table mig_audit_log on commit drop as select * from evidence.audit_log;
drop table evidence.audit_log;
create table evidence.audit_log (id uuid not null, tenant_id uuid not null, entity_ref text collate "C" not null, period_ref text collate "C" not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, primary key (tenant_id, entity_ref, period_ref, id), unique (tenant_id, entity_ref, period_ref, seq)) partition by range (tenant_id, entity_ref, period_ref);
create table evidence.audit_log_default partition of evidence.audit_log default;

do $$
declare
    v_table text;
    v_key record;
    v_id text;
begin
    for v_key in
        select tenant_id, entity_ref, period_ref from (
            select tenant_id, entity_ref, period_ref from pg_temp.mig_decision_trace
            union
            select tenant_id, entity_ref, period_ref from pg_temp.mig_evidence_payload
            union
            select tenant_id, entity_ref, period_ref from pg_temp.mig_acknowledgement
            union
            select tenant_id, entity_ref, period_ref from pg_temp.mig_audit_log
        ) k order by 1, 2, 3
    loop
        v_id := 'rp_' || replace(gen_random_uuid()::text, '-', '');
        insert into evidence.retention_partition (partition_id, tenant_id, entity_ref, period_from, period_to, state)
        values (v_id, v_key.tenant_id, v_key.entity_ref, v_key.period_ref, v_key.period_ref, 'ATTACHED');
        foreach v_table in array array['decision_trace', 'evidence_payload', 'acknowledgement', 'audit_log'] loop
            execute format('create table evidence.%I partition of evidence.%I for values from (%L, %L, %L) to (%L, %L, %L)',
                v_table || '_' || v_id, v_table, v_key.tenant_id, v_key.entity_ref, v_key.period_ref,
                v_key.tenant_id, v_key.entity_ref, v_key.period_ref || chr(1));
        end loop;
    end loop;
end
$$;

insert into evidence.decision_trace select * from pg_temp.mig_decision_trace;
insert into evidence.evidence_payload select * from pg_temp.mig_evidence_payload;
insert into evidence.acknowledgement select * from pg_temp.mig_acknowledgement;
insert into evidence.audit_log select * from pg_temp.mig_audit_log;

comment on column evidence.decision_trace.content is 'class: content';
ALTER TABLE evidence.decision_trace ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.decision_trace FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.decision_trace
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
create policy archive_volume_sys_select on evidence.decision_trace for select to evidence_sys using (true);
grant select (tenant_id, entity_ref) on evidence.decision_trace to evidence_sys;
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.decision_trace FROM evidence_runtime;
CREATE TRIGGER decision_trace_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.decision_trace
    FOR EACH ROW EXECUTE FUNCTION evidence.decision_trace_immutable();
grant select, insert on evidence.decision_trace to evidence_runtime;

comment on column evidence.evidence_payload.content is 'class: content';
ALTER TABLE evidence.evidence_payload ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.evidence_payload FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.evidence_payload
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
create policy archive_volume_sys_select on evidence.evidence_payload for select to evidence_sys using (true);
grant select (tenant_id, entity_ref) on evidence.evidence_payload to evidence_sys;
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.evidence_payload FROM evidence_runtime;
CREATE TRIGGER evidence_payload_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.evidence_payload
    FOR EACH ROW EXECUTE FUNCTION evidence.evidence_payload_immutable();
grant select, insert on evidence.evidence_payload to evidence_runtime;

comment on column evidence.acknowledgement.content is 'class: content';
ALTER TABLE evidence.acknowledgement ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.acknowledgement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.acknowledgement
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
create policy archive_volume_sys_select on evidence.acknowledgement for select to evidence_sys using (true);
grant select (tenant_id, entity_ref) on evidence.acknowledgement to evidence_sys;
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.acknowledgement FROM evidence_runtime;
CREATE TRIGGER acknowledgement_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.acknowledgement
    FOR EACH ROW EXECUTE FUNCTION evidence.acknowledgement_immutable();
grant select, insert on evidence.acknowledgement to evidence_runtime;

comment on column evidence.audit_log.content is 'class: content';
ALTER TABLE evidence.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.audit_log
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
create policy archive_volume_sys_select on evidence.audit_log for select to evidence_sys using (true);
grant select (tenant_id, entity_ref) on evidence.audit_log to evidence_sys;
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.audit_log FROM evidence_runtime;
CREATE TRIGGER audit_log_no_mutation
    BEFORE UPDATE OR DELETE ON evidence.audit_log
    FOR EACH ROW EXECUTE FUNCTION evidence.audit_log_immutable();
grant select, insert on evidence.audit_log to evidence_runtime;

create index decision_trace_doc_version_idx on evidence.decision_trace (tenant_id, doc_uuid, version);

ALTER TABLE evidence.retention_partition ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.retention_partition FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.retention_partition
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.retention_partition FROM evidence_runtime;
grant select on evidence.retention_partition to evidence_runtime;

create table evidence.legal_hold (
    hold_id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    reason text not null,
    status text not null check (status in ('ACTIVE', 'RELEASED')),
    placed_at timestamptz not null,
    placed_by text not null,
    released_at timestamptz,
    released_by text,
    release_confirmed_by text,
    check ((status = 'RELEASED') = (released_at is not null))
);
comment on column evidence.legal_hold.reason is 'class: content';
create unique index legal_hold_one_active on evidence.legal_hold (tenant_id, entity_ref, period_ref) where status = 'ACTIVE';

ALTER TABLE evidence.legal_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.legal_hold FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.legal_hold
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.legal_hold FROM evidence_runtime;
REVOKE DELETE ON evidence.legal_hold FROM evidence_runtime;
grant select, insert on evidence.legal_hold to evidence_runtime;
grant update (status, released_at, released_by, release_confirmed_by) on evidence.legal_hold to evidence_runtime;

create table evidence.retention_notice (
    tenant_id uuid not null,
    partition_id text not null references evidence.retention_partition (partition_id),
    notified_at timestamptz not null,
    window_days integer not null check (window_days >= 0),
    window_closes_at timestamptz not null,
    unique (tenant_id, partition_id, notified_at)
);

ALTER TABLE evidence.retention_notice ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.retention_notice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.retention_notice
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.retention_notice FROM evidence_runtime;
grant select, insert on evidence.retention_notice to evidence_runtime;

create table evidence.retention_approval (
    tenant_id uuid not null,
    partition_id text not null references evidence.retention_partition (partition_id),
    approver_user_id text not null check (approver_user_id <> ''),
    permission_key text not null check (permission_key <> ''),
    approved_at timestamptz not null,
    unique (tenant_id, partition_id, approver_user_id)
);

ALTER TABLE evidence.retention_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.retention_approval FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.retention_approval
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.retention_approval FROM evidence_runtime;
grant select, insert on evidence.retention_approval to evidence_runtime;

-- The partition of one (tenant, entity, period), created on first use. A tenant creates only its own partitions
-- (the signed binding decides); a period whose partition was already detached, archived or dropped is refused.
create function evidence.ensure_partition(p_tenant uuid, p_entity text, p_period text) returns text
language plpgsql security definer set search_path = evidence, pg_temp as $$
declare
    v_tenant uuid := (select evidence.bound_tenant());
    v_id text;
    v_table text;
begin
    if v_tenant is null or p_tenant is distinct from v_tenant then
        raise exception 'a partition is created only in its own tenant transaction' using errcode = '42501';
    end if;
    if p_entity is null or p_period is null or position(chr(1) in p_entity || p_period) > 0 then
        raise exception 'entity and period must be set and free of control character 1' using errcode = '22023';
    end if;
    perform pg_advisory_xact_lock(hashtext(p_tenant::text || ':' || p_entity), hashtext('partition:' || p_period));
    select r.partition_id into v_id from evidence.retention_partition r
        where r.tenant_id = p_tenant and r.entity_ref = p_entity and r.period_from = p_period and r.state = 'ATTACHED';
    if found then
        return v_id;
    end if;
    if exists (select 1 from evidence.retention_partition r
               where r.tenant_id = p_tenant and r.entity_ref = p_entity and r.period_from = p_period) then
        raise exception 'the partition of this period is no longer attached' using errcode = '55000';
    end if;
    -- rows a direct insert put in a default partition stay there: a new partition could not be attached beside them
    if exists (select 1 from evidence.decision_trace_default d where d.tenant_id = p_tenant and d.entity_ref = p_entity and d.period_ref = p_period)
       or exists (select 1 from evidence.evidence_payload_default d where d.tenant_id = p_tenant and d.entity_ref = p_entity and d.period_ref = p_period)
       or exists (select 1 from evidence.acknowledgement_default d where d.tenant_id = p_tenant and d.entity_ref = p_entity and d.period_ref = p_period)
       or exists (select 1 from evidence.audit_log_default d where d.tenant_id = p_tenant and d.entity_ref = p_entity and d.period_ref = p_period) then
        return null;
    end if;
    v_id := 'rp_' || replace(gen_random_uuid()::text, '-', '');
    insert into evidence.retention_partition (partition_id, tenant_id, entity_ref, period_from, period_to, state)
    values (v_id, p_tenant, p_entity, p_period, p_period, 'ATTACHED');
    foreach v_table in array array['decision_trace', 'evidence_payload', 'acknowledgement', 'audit_log'] loop
        execute format('create table evidence.%I partition of evidence.%I for values from (%L, %L, %L) to (%L, %L, %L)',
            v_table || '_' || v_id, v_table, p_tenant, p_entity, p_period, p_tenant, p_entity, p_period || chr(1));
    end loop;
    return v_id;
end
$$;

-- Detach of an expired partition. Every refusal is raised before anything changes; the checks fail closed.
create function evidence.detach_expired_partition(p_partition_id text, p_audit_ref text, p_retention_end timestamptz)
    returns jsonb
language plpgsql security definer set search_path = evidence, pg_temp as $$
declare
    v_tenant uuid := (select evidence.bound_tenant());
    v_part evidence.retention_partition%rowtype;
    v_tails jsonb;
    v_table text;
    v_hash text;
begin
    if v_tenant is null then
        raise exception 'no tenant is bound' using errcode = '42501';
    end if;
    select * into v_part from evidence.retention_partition r
        where r.partition_id = p_partition_id and r.tenant_id = v_tenant for update;
    if not found then
        raise exception 'unknown partition' using errcode = 'P0002';
    end if;
    if v_part.state <> 'ATTACHED' then
        raise exception 'the partition is not attached' using errcode = '55000';
    end if;
    if p_audit_ref is null or btrim(p_audit_ref) = '' then
        raise exception 'the audit entry of the deletion is required' using errcode = '23514';
    end if;
    if p_retention_end is null or p_retention_end > clock_timestamp() then
        raise exception 'the retention end has not been reached' using errcode = '55000';
    end if;
    if exists (select 1 from evidence.legal_hold h where h.tenant_id = v_tenant and h.entity_ref = v_part.entity_ref
               and h.period_ref = v_part.period_from and h.status = 'ACTIVE') then
        raise exception 'a legal hold is active' using errcode = '55000';
    end if;
    if not exists (select 1 from evidence.retention_notice n where n.tenant_id = v_tenant and n.partition_id = p_partition_id)
       or exists (select 1 from evidence.retention_notice n where n.tenant_id = v_tenant and n.partition_id = p_partition_id
                  and n.window_closes_at > clock_timestamp()) then
        raise exception 'the notice window has not closed' using errcode = '55000';
    end if;
    if (select count(distinct a.approver_user_id) from evidence.retention_approval a
        where a.tenant_id = v_tenant and a.partition_id = p_partition_id) < 2 then
        raise exception 'two distinct approvers are required' using errcode = '55000';
    end if;
    if exists (select 1 from evidence.integrity_failure f where f.tenant_id = v_tenant and f.entity_ref = v_part.entity_ref
               and f.period_ref = v_part.period_from)
       or coalesce((select i.result from evidence.integrity_run i where i.tenant_id = v_tenant
                    and i.entity_ref = v_part.entity_ref and i.period_ref = v_part.period_from
                    order by i.run_at desc, i.seq desc limit 1), 'VERIFIED') = 'FAILED' then
        raise exception 'chain verification has failed for this period' using errcode = '55000';
    end if;
    v_tails := '{}'::jsonb;
    foreach v_table in array array['decision_trace', 'evidence_payload', 'acknowledgement', 'audit_log'] loop
        execute format('select content_hash from evidence.%I where tenant_id = $1 and entity_ref = $2 and period_ref = $3 order by seq desc limit 1', v_table)
            into v_hash using v_tenant, v_part.entity_ref, v_part.period_from;
        v_tails := v_tails || jsonb_build_object(v_table, v_hash);
    end loop;
    foreach v_table in array array['decision_trace', 'evidence_payload', 'acknowledgement', 'audit_log'] loop
        execute format('alter table evidence.%I detach partition evidence.%I', v_table, v_table || '_' || p_partition_id);
    end loop;
    update evidence.retention_partition r
       set state = 'DETACHED', tail_hashes = v_tails, retention_end = p_retention_end, detached_at = clock_timestamp()
     where r.partition_id = p_partition_id and r.tenant_id = v_tenant;
    return v_tails;
end
$$;

create function evidence.mark_partition_archived(p_partition_id text, p_archive_ref text) returns void
language plpgsql security definer set search_path = evidence, pg_temp as $$
declare
    v_tenant uuid := (select evidence.bound_tenant());
begin
    if v_tenant is null then
        raise exception 'no tenant is bound' using errcode = '42501';
    end if;
    if p_archive_ref is null or btrim(p_archive_ref) = '' then
        raise exception 'the archive reference is required' using errcode = '23514';
    end if;
    update evidence.retention_partition r
       set state = 'ARCHIVED', archive_ref = p_archive_ref, archived_at = clock_timestamp()
     where r.partition_id = p_partition_id and r.tenant_id = v_tenant and r.state = 'DETACHED';
    if not found then
        raise exception 'the partition is not detached' using errcode = '55000';
    end if;
end
$$;

create function evidence.drop_detached_partition(p_partition_id text) returns void
language plpgsql security definer set search_path = evidence, pg_temp as $$
declare
    v_tenant uuid := (select evidence.bound_tenant());
    v_table text;
begin
    if v_tenant is null then
        raise exception 'no tenant is bound' using errcode = '42501';
    end if;
    perform 1 from evidence.retention_partition r
        where r.partition_id = p_partition_id and r.tenant_id = v_tenant and r.state = 'ARCHIVED' for update;
    if not found then
        raise exception 'only an archived partition is dropped' using errcode = '55000';
    end if;
    foreach v_table in array array['decision_trace', 'evidence_payload', 'acknowledgement', 'audit_log'] loop
        execute format('drop table evidence.%I', v_table || '_' || p_partition_id);
    end loop;
    update evidence.retention_partition r set state = 'DROPPED', dropped_at = clock_timestamp()
     where r.partition_id = p_partition_id and r.tenant_id = v_tenant;
end
$$;

revoke all on function evidence.ensure_partition(uuid, text, text) from public;
revoke all on function evidence.detach_expired_partition(text, text, timestamptz) from public;
revoke all on function evidence.mark_partition_archived(text, text) from public;
revoke all on function evidence.drop_detached_partition(text) from public;
grant execute on function evidence.ensure_partition(uuid, text, text) to evidence_runtime;
grant execute on function evidence.detach_expired_partition(text, text, timestamptz) to evidence_runtime;
grant execute on function evidence.mark_partition_archived(text, text) to evidence_runtime;
grant execute on function evidence.drop_detached_partition(text) to evidence_runtime;

