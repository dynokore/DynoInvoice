-- The once-only record of the build of a version (INV-T-23007; INV-AC-23006). The row is inserted by the build that
-- stages PayloadReady or PayloadRejected, in the same transaction. Select and insert only.
create table payload.build_outcome (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    outcome text not null,
    primary key (tenant_id, doc_uuid, version)
);
ALTER TABLE payload.build_outcome ENABLE ROW LEVEL SECURITY;
ALTER TABLE payload.build_outcome FORCE ROW LEVEL SECURITY;
create policy build_outcome_tenant_isolation on payload.build_outcome
    using      (tenant_id = (select payload.bound_tenant()))
    with check (tenant_id = (select payload.bound_tenant()));
grant usage on schema payload to payload_runtime;
grant select, insert on payload.build_outcome to payload_runtime;
