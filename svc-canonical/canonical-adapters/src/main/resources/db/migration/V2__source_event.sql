-- V2__source_event.sql (INV-T-5544; INV-REQ-1191, FR-A.2). The idempotency record of an accounting event and its
-- binding to a Doc UUID. Append-only: the runtime login holds INSERT and SELECT only (INV-AC-7865).
-- The column that holds the business term tenant is named tenant_id (INV-Q-8069, platform-build tenant-column lint).
-- No foreign key to the document table: the document storage task creates it.
create table canonical.source_event (
    tenant_id uuid not null,
    source_system text not null,
    source_key text not null,
    source_revision bigint not null,
    doc_uuid uuid not null,
    unique (tenant_id, source_system, source_key, source_revision)
);
ALTER TABLE canonical.source_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.source_event FORCE ROW LEVEL SECURITY;
CREATE POLICY source_event_tenant_isolation ON canonical.source_event
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
grant select, insert on canonical.source_event to canonical_runtime;
