-- V15__archive_volume_quarantine.sql (INV-T-20567; INV-AC-20663).
--
-- A quarantined payload is retained evidence of what a tenant sent, so payload_quarantine.payload_bytes is a covered
-- content column of the archive volume. Like a pending record it has no entity column: it belongs to the entity of the
-- document_locator of its (tenant_id, doc_uuid, version). evidence_sys reads the key columns only, never payload_bytes.
-- held_payload is transient working state and stays out of the volume and of the catalogue.

create policy archive_volume_sys_select on evidence.payload_quarantine for select to evidence_sys using (true);
grant select (tenant_id, doc_uuid, version) on evidence.payload_quarantine to evidence_sys;

grant create on schema evidence to evidence_sys;
create or replace function evidence.archive_volume_entities() returns table (tenant_id uuid, entity_ref text)
language sql stable security definer set search_path = pg_catalog, evidence, pg_temp as $$
    select p.tenant_id, p.entity_ref from evidence.evidence_payload p
    union select a.tenant_id, a.entity_ref from evidence.acknowledgement a
    union select d.tenant_id, d.entity_ref from evidence.decision_trace d
    union select l.tenant_id, l.entity_ref from evidence.audit_log l
    union select k.tenant_id, k.entity_ref from evidence.pending_record r
        join evidence.document_locator k on k.tenant_id = r.tenant_id and k.doc_uuid = r.doc_uuid
            and k.version = r.version
    union select k.tenant_id, k.entity_ref from evidence.payload_quarantine q
        join evidence.document_locator k on k.tenant_id = q.tenant_id and k.doc_uuid = q.doc_uuid
            and k.version = q.version
    union select v.tenant_id, v.entity_ref from evidence.archive_volume_published v where v.bytes > 0
$$;
alter function evidence.archive_volume_entities() owner to evidence_sys;
revoke all on function evidence.archive_volume_entities() from public;
grant execute on function evidence.archive_volume_entities() to evidence_runtime;
revoke create on schema evidence from evidence_sys;
