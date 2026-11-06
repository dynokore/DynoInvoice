-- The entity and the SGD total of each canonical version (INV-T-17181; INV-REQ-1293, INV-AC-17179). A version is immutable: select and insert only.
create table payload.document_version_fact (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    entity_ref text not null check (entity_ref !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'),
    total_sgd numeric(19,2),
    recorded_at timestamptz not null default now(),
    primary key (tenant_id, doc_uuid, version)
);
ALTER TABLE payload.document_version_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE payload.document_version_fact FORCE ROW LEVEL SECURITY;
create policy document_version_fact_tenant_isolation on payload.document_version_fact
    using      (tenant_id = (select payload.bound_tenant()))
    with check (tenant_id = (select payload.bound_tenant()));
grant usage on schema payload to payload_runtime;
grant select, insert on payload.document_version_fact to payload_runtime;
