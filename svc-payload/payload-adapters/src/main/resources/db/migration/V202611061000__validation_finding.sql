-- The BLOCK findings of a validated document version (INV-T-17036; INV-REQ-1180, INV-REQ-500, INV-AC-17037).
create table payload.validation_finding (
    id bigint generated always as identity primary key,
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    code text not null check (char_length(code) between 1 and 20),
    field text not null default '',
    message text not null,
    overridable boolean not null,
    recorded_at timestamptz not null default now()
);
create unique index validation_finding_key on payload.validation_finding (tenant_id, doc_uuid, version, code, field);
ALTER TABLE payload.validation_finding ENABLE ROW LEVEL SECURITY;
ALTER TABLE payload.validation_finding FORCE ROW LEVEL SECURITY;
create policy validation_finding_tenant_isolation on payload.validation_finding
    using      (tenant_id = (select payload.bound_tenant()))
    with check (tenant_id = (select payload.bound_tenant()));
grant usage on schema payload to payload_runtime;
grant select, insert, delete on payload.validation_finding to payload_runtime;
