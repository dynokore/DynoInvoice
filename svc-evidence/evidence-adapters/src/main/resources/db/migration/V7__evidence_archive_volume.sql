-- V7__evidence_archive_volume.sql (INV-T-12698; INV-Q-12683 A, INV-Q-12685 A, INV-Q-12687 A).
--
-- The last published archive volume of each entity, and the catalogue of the entities that may hold volume. The
-- ledger row is rewritten in place (select, insert, update; no delete) and is tenant-isolated the way the other
-- tenant tables are. archive_volume_entities() is the one cross-tenant read: SECURITY DEFINER, owned by the NOLOGIN
-- evidence_sys, a fixed search_path, and it returns the key columns (tenant_id, entity_ref) only (TPL-09). evidence_sys
-- reads the covered tables through a select policy and a grant on the key columns, never on a content column.
-- A pending_record has no entity column: it belongs to the entity of the document_locator of its (tenant_id,
-- doc_uuid, version).

create table evidence.archive_volume_published (
    tenant_id uuid not null,
    entity_ref text not null,
    bytes bigint not null check (bytes >= 0),
    measured_at timestamptz not null,
    primary key (tenant_id, entity_ref)
);

ALTER TABLE evidence.archive_volume_published ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.archive_volume_published FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.archive_volume_published
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
grant select, insert, update on evidence.archive_volume_published to evidence_runtime;

create policy archive_volume_sys_select on evidence.archive_volume_published for select to evidence_sys using (true);
grant select (tenant_id, entity_ref, bytes) on evidence.archive_volume_published to evidence_sys;
create policy archive_volume_sys_select on evidence.evidence_payload for select to evidence_sys using (true);
grant select (tenant_id, entity_ref) on evidence.evidence_payload to evidence_sys;
create policy archive_volume_sys_select on evidence.acknowledgement for select to evidence_sys using (true);
grant select (tenant_id, entity_ref) on evidence.acknowledgement to evidence_sys;
create policy archive_volume_sys_select on evidence.decision_trace for select to evidence_sys using (true);
grant select (tenant_id, entity_ref) on evidence.decision_trace to evidence_sys;
create policy archive_volume_sys_select on evidence.audit_log for select to evidence_sys using (true);
grant select (tenant_id, entity_ref) on evidence.audit_log to evidence_sys;
create policy archive_volume_sys_select on evidence.pending_record for select to evidence_sys using (true);
grant select (tenant_id, doc_uuid, version) on evidence.pending_record to evidence_sys;
create policy archive_volume_sys_select on evidence.document_locator for select to evidence_sys using (true);
grant select (tenant_id, doc_uuid, version, entity_ref) on evidence.document_locator to evidence_sys;

create function evidence.archive_volume_entities() returns table (tenant_id uuid, entity_ref text)
language sql stable security definer set search_path = pg_catalog, evidence, pg_temp as $$
    select p.tenant_id, p.entity_ref from evidence.evidence_payload p
    union select a.tenant_id, a.entity_ref from evidence.acknowledgement a
    union select d.tenant_id, d.entity_ref from evidence.decision_trace d
    union select l.tenant_id, l.entity_ref from evidence.audit_log l
    union select k.tenant_id, k.entity_ref from evidence.pending_record r
        join evidence.document_locator k on k.tenant_id = r.tenant_id and k.doc_uuid = r.doc_uuid
            and k.version = r.version
    union select v.tenant_id, v.entity_ref from evidence.archive_volume_published v where v.bytes > 0
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.archive_volume_entities() owner to evidence_sys;
revoke all on function evidence.archive_volume_entities() from public;
grant execute on function evidence.archive_volume_entities() to evidence_runtime;
revoke create on schema evidence from evidence_sys;
