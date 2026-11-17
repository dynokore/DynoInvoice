-- V24__prior_evidence.sql (INV-T-24308; INV-REQ-639, INV-AC-24306).
--
-- Slice 1 of importPriorEvidence: evidence.prior_evidence holds imported prior submission evidence (no content bytes,
-- no sequence, no hash chain) and is one more member (the fifth) of each retention unit, so retention detach, archive
-- and drop treat it like the chain tables (NIST SP 800-53 AU-9, AU-11). Append-only for the runtime login (select and
-- insert only; least privilege), row level security by the signed tenant binding, the acting user id is stored, never
-- a role. The identity rule (entityRef, documentNumber, transactionDate) is enforced by the import use case, because a
-- unique index must contain the partition key. No prior_evidence partition exists until the import slice creates one.

alter table evidence.retention_partition_member drop constraint retention_partition_member_chain_table_check;
alter table evidence.retention_partition_member add constraint retention_partition_member_chain_table_check
    check (chain_table in ('decision_trace', 'evidence_payload', 'acknowledgement', 'audit_log', 'prior_evidence'));

create table evidence.prior_evidence (id uuid not null, tenant_id uuid not null, entity_ref text collate "C" not null, period_ref text collate "C" not null, document_number text not null check (document_number <> ''), transaction_date date not null, acknowledgement_ref text not null check (acknowledgement_ref <> ''), prior_solution text not null check (prior_solution <> ''), imported_at timestamptz not null, imported_by text not null check (imported_by <> ''), constraint prior_evidence_pkey primary key (tenant_id, entity_ref, period_ref, id)) partition by range (tenant_id, entity_ref, period_ref);
create index prior_evidence_ack_idx on evidence.prior_evidence (tenant_id, acknowledgement_ref);
create index prior_evidence_doc_idx on evidence.prior_evidence (tenant_id, entity_ref, document_number, transaction_date);

ALTER TABLE evidence.prior_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.prior_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.prior_evidence
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
create policy archive_volume_sys_select on evidence.prior_evidence for select to evidence_sys using (true);
grant select (tenant_id, entity_ref) on evidence.prior_evidence to evidence_sys;
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.prior_evidence FROM evidence_runtime;
grant select, insert on evidence.prior_evidence to evidence_runtime;

create or replace function evidence.create_retention_partition(
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
    elsif p_parent = 'evidence.prior_evidence'::regclass then
        v_chain := 'prior_evidence';
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

create or replace function evidence.detach_expired_partition(
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
    elsif p_parent = 'evidence.prior_evidence'::regclass then
        v_chain := 'prior_evidence';
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
    elsif v_chain = 'audit_log' then
        select t.content_hash into v_hash from evidence.audit_log t where t.tenant_id = v_tenant
            and t.entity_ref = v_unit.entity_ref and t.period_ref = v_unit.period_from order by t.seq desc limit 1;
    else
        v_hash := null; -- prior_evidence is not a chain: no sequence, no hash, so no tail hash
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

create or replace function evidence.mark_partition_archived(p_partition_id text, p_archive_ref text) returns void
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
       or (select count(*) from evidence.retention_partition_member m where m.partition_id = p_partition_id
           and m.chain_table in ('decision_trace', 'evidence_payload', 'acknowledgement', 'audit_log')) <> 4 then
        raise exception 'the partition is not detached' using errcode = '55000';
    end if;
    update evidence.retention_partition_member m set state = 'ARCHIVED'
     where m.partition_id = p_partition_id and m.tenant_id = v_tenant;
    update evidence.retention_partition r
       set state = 'ARCHIVED', archive_ref = p_archive_ref, archived_at = clock_timestamp()
     where r.partition_id = p_partition_id and r.tenant_id = v_tenant;
end
$$;
-- The replaced function keeps its grants; the owner statements are repeated for the migration lint (V21 style).
grant create on schema evidence to evidence_sys;
alter function evidence.mark_partition_archived(text, text) owner to evidence_sys;
revoke create on schema evidence from evidence_sys;
