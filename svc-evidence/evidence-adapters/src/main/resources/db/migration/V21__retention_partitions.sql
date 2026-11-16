-- V21__retention_partitions.sql (INV-T-22969; INV-REQ-639, INV-AC-22962; owner answer 22842 A, 2026-11-15, and the
-- partition-creation ruling A, 2026-11-16).
--
-- The storage side of retention-end deletion. The four chain tables become declarative range partitions by
-- (tenant_id, entity_ref, period_ref). evidence.retention_partition is the registry (one row per tenant, entity and
-- period range) and the only map from a partitionId to its tenant, entity and range; evidence.retention_partition_member
-- holds one row per chain table of a unit (the migration lint's functions work on one table per call).
--
-- Bound arithmetic: a unit is one period_ref, the range [period_ref, period_ref || chr(1)) on the "C" collation.
-- chr(1) sorts below every printable character, so P1 and P10 are in different units and no two units overlap.
--
-- Partitions are created at run time only by evidence.create_retention_partition (one EXECUTE of the shape the
-- migration lint allows), called by the wiring before an append and by this migration for the existing rows.
-- Deletion: detach_expired_partition and drop_detached_partition (owned by evidence_migrate, the pair the lint allows)
-- and mark_partition_archived (owned by evidence_sys). All are SECURITY DEFINER with search_path = pg_catalog,
-- evidence, pg_temp and executable by the runtime login only. The runtime login holds no DELETE, TRUNCATE or DDL right
-- on any chain table or partition (NIST SP 800-53 AU-9, AC-5, AC-6; OWASP ASVS V1, V4.1.1).
-- Chain verification (DEP-007) is read from evidence.integrity_failure (open as long as a row exists, V7) and from the
-- latest evidence.integrity_run.

-- The migrate-owned functions evaluate the signed binding themselves, as the sys-owned functions of V7 and V10 do.
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

create table evidence.retention_partition_member (
    partition_id text not null references evidence.retention_partition (partition_id),
    tenant_id uuid not null,
    chain_table text not null check (chain_table in ('decision_trace', 'evidence_payload', 'acknowledgement', 'audit_log')),
    partition_table text not null,
    state text not null check (state in ('ATTACHED', 'DETACHED', 'ARCHIVED', 'DROPPED')),
    tail_hash text,
    primary key (partition_id, chain_table),
    unique (partition_table)
);

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


create table evidence.retention_notice (
    tenant_id uuid not null,
    partition_id text not null references evidence.retention_partition (partition_id),
    notified_at timestamptz not null,
    window_days integer not null check (window_days >= 0),
    window_closes_at timestamptz not null,
    unique (tenant_id, partition_id, notified_at)
);


create table evidence.retention_approval (
    tenant_id uuid not null,
    partition_id text not null references evidence.retention_partition (partition_id),
    approver_user_id text not null check (approver_user_id <> ''),
    permission_key text not null check (permission_key <> ''),
    approved_at timestamptz not null,
    unique (tenant_id, partition_id, approver_user_id)
);

-- The chain tables: each old table is renamed to <table>_legacy after its row level security is switched off (an owner
-- under forced row level security without a tenant binding reads no row, so the copy would lose every old row), the
-- partitioned table is created, the rows are copied and the legacy table is dropped. Static SQL only.
alter table evidence.decision_trace no force row level security;
alter table evidence.decision_trace disable row level security;
alter table evidence.decision_trace rename to decision_trace_legacy;
alter table evidence.decision_trace_legacy rename constraint decision_trace_pkey to decision_trace_legacy_pkey;
alter table evidence.decision_trace_legacy rename constraint decision_trace_tenant_id_entity_ref_period_ref_seq_key to decision_trace_legacy_seq_key;
create table evidence.decision_trace (id uuid not null, tenant_id uuid not null, entity_ref text collate "C" not null, period_ref text collate "C" not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, version integer check (version is null or version >= 1), constraint decision_trace_pkey primary key (tenant_id, entity_ref, period_ref, id), constraint decision_trace_tenant_id_entity_ref_period_ref_seq_key unique (tenant_id, entity_ref, period_ref, seq)) partition by range (tenant_id, entity_ref, period_ref);
alter table evidence.evidence_payload no force row level security;
alter table evidence.evidence_payload disable row level security;
alter table evidence.evidence_payload rename to evidence_payload_legacy;
alter table evidence.evidence_payload_legacy rename constraint evidence_payload_pkey to evidence_payload_legacy_pkey;
alter table evidence.evidence_payload_legacy rename constraint evidence_payload_tenant_id_entity_ref_period_ref_seq_key to evidence_payload_legacy_seq_key;
create table evidence.evidence_payload (id uuid not null, tenant_id uuid not null, entity_ref text collate "C" not null, period_ref text collate "C" not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, constraint evidence_payload_pkey primary key (tenant_id, entity_ref, period_ref, id), constraint evidence_payload_tenant_id_entity_ref_period_ref_seq_key unique (tenant_id, entity_ref, period_ref, seq)) partition by range (tenant_id, entity_ref, period_ref);
alter table evidence.acknowledgement no force row level security;
alter table evidence.acknowledgement disable row level security;
alter table evidence.acknowledgement rename to acknowledgement_legacy;
alter table evidence.acknowledgement_legacy rename constraint acknowledgement_pkey to acknowledgement_legacy_pkey;
alter table evidence.acknowledgement_legacy rename constraint acknowledgement_tenant_id_entity_ref_period_ref_seq_key to acknowledgement_legacy_seq_key;
create table evidence.acknowledgement (id uuid not null, tenant_id uuid not null, entity_ref text collate "C" not null, period_ref text collate "C" not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, constraint acknowledgement_pkey primary key (tenant_id, entity_ref, period_ref, id), constraint acknowledgement_tenant_id_entity_ref_period_ref_seq_key unique (tenant_id, entity_ref, period_ref, seq)) partition by range (tenant_id, entity_ref, period_ref);
alter table evidence.audit_log no force row level security;
alter table evidence.audit_log disable row level security;
alter table evidence.audit_log rename to audit_log_legacy;
alter table evidence.audit_log_legacy rename constraint audit_log_pkey to audit_log_legacy_pkey;
alter table evidence.audit_log_legacy rename constraint audit_log_tenant_id_entity_ref_period_ref_seq_key to audit_log_legacy_seq_key;
create table evidence.audit_log (id uuid not null, tenant_id uuid not null, entity_ref text collate "C" not null, period_ref text collate "C" not null, seq bigint not null, evidence_type text not null, content bytea not null, content_hash text not null, previous_hash text, recorded_at timestamptz not null, doc_uuid uuid, invoice_number text, counterparty_uen text, category_code text, constraint audit_log_pkey primary key (tenant_id, entity_ref, period_ref, id), constraint audit_log_tenant_id_entity_ref_period_ref_seq_key unique (tenant_id, entity_ref, period_ref, seq)) partition by range (tenant_id, entity_ref, period_ref);

-- Creation of the partition of one chain table for one (tenant, entity, period) unit: the only place a partition is
-- created. The first EXECUTE argument is the declared variable v_partition, built here from the registry id, never from
-- a parameter. A tenant creates only its own partitions (the signed binding decides); the migration login may create
-- any, for the rows that exist before this migration.
create function evidence.create_retention_partition(
    p_parent regclass, p_tenant_id uuid, p_entity_ref text, p_period_from text, p_period_to text)
    returns text
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_partition text;
    v_chain text;
    v_id text;
    v_state text;
    v_member_state text;
begin
    if p_parent = 'evidence.decision_trace'::regclass then
        v_chain := 'decision_trace';
    elsif p_parent = 'evidence.evidence_payload'::regclass then
        v_chain := 'evidence_payload';
    elsif p_parent = 'evidence.acknowledgement'::regclass then
        v_chain := 'acknowledgement';
    elsif p_parent = 'evidence.audit_log'::regclass then
        v_chain := 'audit_log';
    else
        raise exception 'not a chain table' using errcode = '22023';
    end if;
    if p_tenant_id is null or p_entity_ref is null or p_period_from is null
       or p_period_to is distinct from p_period_from || chr(1)
       or position(chr(1) in p_entity_ref || p_period_from) > 0 then
        raise exception 'tenant, entity and period must be set; the range is one period' using errcode = '22023';
    end if;
    if (select evidence.bound_tenant()) is distinct from p_tenant_id and session_user::text <> 'evidence_migrate' then
        raise exception 'a partition is created only in its own tenant transaction' using errcode = '42501';
    end if;
    perform pg_advisory_xact_lock(hashtext(p_tenant_id::text || ':' || p_entity_ref), hashtext(v_chain || ':' || p_period_from));
    select r.partition_id, r.state into v_id, v_state from evidence.retention_partition r
        where r.tenant_id = p_tenant_id and r.entity_ref = p_entity_ref
          and r.period_from = p_period_from and r.period_to = p_period_to;
    if found then
        select m.state into v_member_state from evidence.retention_partition_member m
            where m.partition_id = v_id and m.chain_table = v_chain;
        if found then
            if v_member_state <> 'ATTACHED' then
                raise exception 'the partition of this period is no longer attached' using errcode = '55000';
            end if;
            return v_id;
        end if;
        if v_state <> 'ATTACHED' then
            raise exception 'the partition of this period is no longer attached' using errcode = '55000';
        end if;
    else
        v_id := 'rp_' || replace(gen_random_uuid()::text, '-', '');
        insert into evidence.retention_partition (partition_id, tenant_id, entity_ref, period_from, period_to, state)
        values (v_id, p_tenant_id, p_entity_ref, p_period_from, p_period_to, 'ATTACHED');
    end if;
    v_partition := format('%I.%I', 'evidence', v_chain || '_' || v_id);
    execute format('CREATE TABLE %s PARTITION OF %s FOR VALUES FROM (%L, %L, %L) TO (%L, %L, %L)', v_partition, p_parent, p_tenant_id, p_entity_ref, p_period_from, p_tenant_id, p_entity_ref, p_period_to);
    insert into evidence.retention_partition_member (partition_id, tenant_id, chain_table, partition_table, state)
    values (v_id, p_tenant_id, v_chain, v_chain || '_' || v_id, 'ATTACHED');
    return v_id;
end
$$;

-- Detach of an expired partition of one chain table. Every refusal is raised before anything changes (fail closed).
create function evidence.detach_expired_partition(
    p_parent regclass, p_partition regclass, p_audit_ref text, p_retention_end timestamptz)
    returns text
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_tenant uuid := (select evidence.bound_tenant());
    v_chain text;
    v_name text;
    v_member evidence.retention_partition_member%rowtype;
    v_unit evidence.retention_partition%rowtype;
    v_hash text;
    v_tails jsonb;
begin
    if v_tenant is null then
        raise exception 'no tenant is bound' using errcode = '42501';
    end if;
    if p_parent = 'evidence.decision_trace'::regclass then
        v_chain := 'decision_trace';
    elsif p_parent = 'evidence.evidence_payload'::regclass then
        v_chain := 'evidence_payload';
    elsif p_parent = 'evidence.acknowledgement'::regclass then
        v_chain := 'acknowledgement';
    elsif p_parent = 'evidence.audit_log'::regclass then
        v_chain := 'audit_log';
    else
        raise exception 'not a chain table' using errcode = '22023';
    end if;
    if not exists (select 1 from pg_inherits i where i.inhparent = p_parent and i.inhrelid = p_partition) then
        raise exception 'the table is not a partition of the chain table' using errcode = '22023';
    end if;
    select c.relname::text into v_name from pg_class c where c.oid = p_partition;
    select * into v_member from evidence.retention_partition_member m
        where m.chain_table = v_chain and m.partition_table = v_name and m.tenant_id = v_tenant for update;
    if not found then
        raise exception 'unknown partition' using errcode = 'P0002';
    end if;
    select * into v_unit from evidence.retention_partition r
        where r.partition_id = v_member.partition_id and r.tenant_id = v_tenant for update;
    if not found or v_unit.state <> 'ATTACHED' or v_member.state <> 'ATTACHED' then
        raise exception 'the partition is not attached' using errcode = '55000';
    end if;
    if p_audit_ref is null or btrim(p_audit_ref) = '' then
        raise exception 'the audit entry of the deletion is required' using errcode = '23514';
    end if;
    if p_retention_end is null or p_retention_end > clock_timestamp() then
        raise exception 'the retention end has not been reached' using errcode = '55000';
    end if;
    if exists (select 1 from evidence.legal_hold h where h.tenant_id = v_tenant and h.entity_ref = v_unit.entity_ref
               and h.period_ref = v_unit.period_from and h.status = 'ACTIVE') then
        raise exception 'a legal hold is active' using errcode = '55000';
    end if;
    if not exists (select 1 from evidence.retention_notice n
                   where n.tenant_id = v_tenant and n.partition_id = v_unit.partition_id)
       or exists (select 1 from evidence.retention_notice n
                  where n.tenant_id = v_tenant and n.partition_id = v_unit.partition_id
                    and n.window_closes_at > clock_timestamp()) then
        raise exception 'the notice window has not closed' using errcode = '55000';
    end if;
    if (select count(distinct a.approver_user_id) from evidence.retention_approval a
        where a.tenant_id = v_tenant and a.partition_id = v_unit.partition_id) < 2 then
        raise exception 'two distinct approvers are required' using errcode = '55000';
    end if;
    if exists (select 1 from evidence.integrity_failure f where f.tenant_id = v_tenant
               and f.entity_ref = v_unit.entity_ref and f.period_ref = v_unit.period_from)
       or coalesce((select i.result from evidence.integrity_run i where i.tenant_id = v_tenant
                    and i.entity_ref = v_unit.entity_ref and i.period_ref = v_unit.period_from
                    order by i.run_at desc, i.seq desc limit 1), 'VERIFIED') = 'FAILED' then
        raise exception 'chain verification has failed for this period' using errcode = '55000';
    end if;
    if v_chain = 'decision_trace' then
        select t.content_hash into v_hash from evidence.decision_trace t where t.tenant_id = v_tenant
            and t.entity_ref = v_unit.entity_ref and t.period_ref = v_unit.period_from order by t.seq desc limit 1;
    elsif v_chain = 'evidence_payload' then
        select t.content_hash into v_hash from evidence.evidence_payload t where t.tenant_id = v_tenant
            and t.entity_ref = v_unit.entity_ref and t.period_ref = v_unit.period_from order by t.seq desc limit 1;
    elsif v_chain = 'acknowledgement' then
        select t.content_hash into v_hash from evidence.acknowledgement t where t.tenant_id = v_tenant
            and t.entity_ref = v_unit.entity_ref and t.period_ref = v_unit.period_from order by t.seq desc limit 1;
    else
        select t.content_hash into v_hash from evidence.audit_log t where t.tenant_id = v_tenant
            and t.entity_ref = v_unit.entity_ref and t.period_ref = v_unit.period_from order by t.seq desc limit 1;
    end if;
    execute format('ALTER TABLE %s DETACH PARTITION %s', p_parent, p_partition);
    update evidence.retention_partition_member m set state = 'DETACHED', tail_hash = v_hash
     where m.partition_id = v_unit.partition_id and m.chain_table = v_chain;
    if not exists (select 1 from evidence.retention_partition_member m
                   where m.partition_id = v_unit.partition_id and m.state = 'ATTACHED') then
        select jsonb_object_agg(m.chain_table, m.tail_hash) into v_tails from evidence.retention_partition_member m
            where m.partition_id = v_unit.partition_id;
        update evidence.retention_partition r
           set state = 'DETACHED', tail_hashes = v_tails, retention_end = p_retention_end,
               detached_at = clock_timestamp()
         where r.partition_id = v_unit.partition_id;
    end if;
    return v_hash;
end
$$;

-- Drop of an archived partition of one chain table (the partition is a detached table, so only the partition is passed).
create function evidence.drop_detached_partition(p_partition regclass) returns void
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_tenant uuid := (select evidence.bound_tenant());
    v_name text;
    v_member evidence.retention_partition_member%rowtype;
begin
    if v_tenant is null then
        raise exception 'no tenant is bound' using errcode = '42501';
    end if;
    select c.relname::text into v_name from pg_class c
        where c.oid = p_partition and c.relnamespace = 'evidence'::regnamespace and c.relkind = 'r';
    if v_name is null or exists (select 1 from pg_inherits i where i.inhrelid = p_partition) then
        raise exception 'not a detached partition table' using errcode = '22023';
    end if;
    select * into v_member from evidence.retention_partition_member m
        where m.partition_table = v_name and m.tenant_id = v_tenant and m.state = 'ARCHIVED' for update;
    if not found then
        raise exception 'only an archived partition is dropped' using errcode = '55000';
    end if;
    execute format('DROP TABLE %s', p_partition);
    update evidence.retention_partition_member m set state = 'DROPPED'
     where m.partition_id = v_member.partition_id and m.chain_table = v_member.chain_table;
    if not exists (select 1 from evidence.retention_partition_member m
                   where m.partition_id = v_member.partition_id and m.state <> 'DROPPED') then
        update evidence.retention_partition r set state = 'DROPPED', dropped_at = clock_timestamp()
         where r.partition_id = v_member.partition_id;
    end if;
end
$$;

-- Archive mark of a detached unit (not a lint-pair function): owned by evidence_sys, static SQL.
create function evidence.mark_partition_archived(p_partition_id text, p_archive_ref text) returns void
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_tenant uuid := (select evidence.bound_tenant());
begin
    if v_tenant is null then
        raise exception 'no tenant is bound' using errcode = '42501';
    end if;
    if p_archive_ref is null or btrim(p_archive_ref) = '' then
        raise exception 'the archive reference is required' using errcode = '23514';
    end if;
    perform 1 from evidence.retention_partition r
        where r.partition_id = p_partition_id and r.tenant_id = v_tenant and r.state = 'DETACHED';
    if not found or exists (select 1 from evidence.retention_partition_member m
                            where m.partition_id = p_partition_id and m.state <> 'DETACHED')
       or (select count(*) from evidence.retention_partition_member m where m.partition_id = p_partition_id) <> 4 then
        raise exception 'the partition is not detached' using errcode = '55000';
    end if;
    update evidence.retention_partition_member m set state = 'ARCHIVED'
     where m.partition_id = p_partition_id and m.tenant_id = v_tenant;
    update evidence.retention_partition r
       set state = 'ARCHIVED', archive_ref = p_archive_ref, archived_at = clock_timestamp()
     where r.partition_id = p_partition_id and r.tenant_id = v_tenant;
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.mark_partition_archived(text, text) owner to evidence_sys;
revoke create on schema evidence from evidence_sys;
grant select on evidence.retention_partition, evidence.retention_partition_member to evidence_sys;
grant update (state, archive_ref, archived_at) on evidence.retention_partition to evidence_sys;
grant update (state) on evidence.retention_partition_member to evidence_sys;
grant execute on function evidence.bound_tenant() to evidence_sys;

revoke all on function evidence.create_retention_partition(regclass, uuid, text, text, text) from public;
revoke all on function evidence.detach_expired_partition(regclass, regclass, text, timestamptz) from public;
revoke all on function evidence.drop_detached_partition(regclass) from public;
revoke all on function evidence.mark_partition_archived(text, text) from public;
grant execute on function evidence.create_retention_partition(regclass, uuid, text, text, text) to evidence_runtime;
grant execute on function evidence.detach_expired_partition(regclass, regclass, text, timestamptz) to evidence_runtime;
grant execute on function evidence.drop_detached_partition(regclass) to evidence_runtime;
grant execute on function evidence.mark_partition_archived(text, text) to evidence_runtime;

-- Homing of the existing rows: one partition per distinct (tenant, entity, period) of each legacy table, created by
-- the migration login through the function above (top-level SELECT statements, no dynamic SQL here).
select evidence.create_retention_partition('evidence.decision_trace'::regclass, k.tenant_id, k.entity_ref, k.period_ref, k.period_ref || chr(1))
    from (select distinct tenant_id, entity_ref, period_ref from evidence.decision_trace_legacy) k;
select evidence.create_retention_partition('evidence.evidence_payload'::regclass, k.tenant_id, k.entity_ref, k.period_ref, k.period_ref || chr(1))
    from (select distinct tenant_id, entity_ref, period_ref from evidence.evidence_payload_legacy) k;
select evidence.create_retention_partition('evidence.acknowledgement'::regclass, k.tenant_id, k.entity_ref, k.period_ref, k.period_ref || chr(1))
    from (select distinct tenant_id, entity_ref, period_ref from evidence.acknowledgement_legacy) k;
select evidence.create_retention_partition('evidence.audit_log'::regclass, k.tenant_id, k.entity_ref, k.period_ref, k.period_ref || chr(1))
    from (select distinct tenant_id, entity_ref, period_ref from evidence.audit_log_legacy) k;
insert into evidence.decision_trace (id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, content_hash, previous_hash, recorded_at, doc_uuid, invoice_number, counterparty_uen, category_code, version) select id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, content_hash, previous_hash, recorded_at, doc_uuid, invoice_number, counterparty_uen, category_code, version from evidence.decision_trace_legacy;
insert into evidence.evidence_payload (id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, content_hash, previous_hash, recorded_at, doc_uuid, invoice_number, counterparty_uen, category_code) select id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, content_hash, previous_hash, recorded_at, doc_uuid, invoice_number, counterparty_uen, category_code from evidence.evidence_payload_legacy;
insert into evidence.acknowledgement (id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, content_hash, previous_hash, recorded_at, doc_uuid, invoice_number, counterparty_uen, category_code) select id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, content_hash, previous_hash, recorded_at, doc_uuid, invoice_number, counterparty_uen, category_code from evidence.acknowledgement_legacy;
insert into evidence.audit_log (id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, content_hash, previous_hash, recorded_at, doc_uuid, invoice_number, counterparty_uen, category_code) select id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, content_hash, previous_hash, recorded_at, doc_uuid, invoice_number, counterparty_uen, category_code from evidence.audit_log_legacy;
drop table evidence.decision_trace_legacy;
drop table evidence.evidence_payload_legacy;
drop table evidence.acknowledgement_legacy;
drop table evidence.audit_log_legacy;

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

ALTER TABLE evidence.retention_partition_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.retention_partition_member FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.retention_partition_member
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.retention_partition_member FROM evidence_runtime;
grant select on evidence.retention_partition_member to evidence_runtime;

ALTER TABLE evidence.legal_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.legal_hold FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.legal_hold
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.legal_hold FROM evidence_runtime;

ALTER TABLE evidence.retention_notice ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.retention_notice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.retention_notice
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.retention_notice FROM evidence_runtime;
grant select, insert on evidence.retention_notice to evidence_runtime;

ALTER TABLE evidence.retention_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.retention_approval FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.retention_approval
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.retention_approval FROM evidence_runtime;
grant select, insert on evidence.retention_approval to evidence_runtime;
grant select, insert on evidence.legal_hold to evidence_runtime;
grant update (status, released_at, released_by, release_confirmed_by) on evidence.legal_hold to evidence_runtime;
