-- The submission type decided for each canonical version (INV-T-22935; INV-AC-22934). A decision is immutable: select and insert only.
create table payload.submission_type_decision (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    submission_type text not null check (submission_type in ('TYPE_1A', 'TYPE_1B', 'TYPE_2', 'TYPE_3')),
    decided_at timestamptz not null default now(),
    primary key (tenant_id, doc_uuid, version)
);
ALTER TABLE payload.submission_type_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE payload.submission_type_decision FORCE ROW LEVEL SECURITY;
create policy submission_type_decision_tenant_isolation on payload.submission_type_decision
    using      (tenant_id = (select payload.bound_tenant()))
    with check (tenant_id = (select payload.bound_tenant()));
grant usage on schema payload to payload_runtime;
grant select, insert on payload.submission_type_decision to payload_runtime;
