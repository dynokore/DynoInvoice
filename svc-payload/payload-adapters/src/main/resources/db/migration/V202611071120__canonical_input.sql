-- The canonical document of each version and its routing facts (INV-T-22935 reads, INV-T-22937 writes). A stored input is immutable: select and insert only.
create table payload.canonical_input (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    canonical_document jsonb not null,
    direction text check (direction in ('SALE', 'PURCHASE')),
    counterparty_on_network boolean,
    primary key (tenant_id, doc_uuid, version)
);
ALTER TABLE payload.canonical_input ENABLE ROW LEVEL SECURITY;
ALTER TABLE payload.canonical_input FORCE ROW LEVEL SECURITY;
create policy canonical_input_tenant_isolation on payload.canonical_input
    using      (tenant_id = (select payload.bound_tenant()))
    with check (tenant_id = (select payload.bound_tenant()));
grant usage on schema payload to payload_runtime;
grant select, insert on payload.canonical_input to payload_runtime;
