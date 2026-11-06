-- The validation overrides of a document version (INV-T-17183; INV-REQ-1180, INV-REQ-1293, INV-Q-10691 A).
-- requested_by and released_by hold user IDs, never roles (INV-Q-10647).
create table payload.validation_override (
    id bigint generated always as identity primary key,
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    code text not null check (char_length(code) between 1 and 20),
    reason text not null check (char_length(btrim(reason)) > 0),
    status text not null check (status in ('PENDING', 'RELEASED')),
    requested_by text not null check (char_length(btrim(requested_by)) > 0),
    requested_at timestamptz not null default now(),
    released_by text check (released_by is null or char_length(btrim(released_by)) > 0),
    released_at timestamptz,
    check ((status = 'RELEASED') = (released_by is not null and released_at is not null)),
    check (status = 'RELEASED' or (released_by is null and released_at is null))
);
create unique index validation_override_key on payload.validation_override (tenant_id, doc_uuid, version, code);
ALTER TABLE payload.validation_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE payload.validation_override FORCE ROW LEVEL SECURITY;
create policy validation_override_tenant_isolation on payload.validation_override
    using      (tenant_id = (select payload.bound_tenant()))
    with check (tenant_id = (select payload.bound_tenant()));
grant usage on schema payload to payload_runtime;
grant select, insert, update on payload.validation_override to payload_runtime;
