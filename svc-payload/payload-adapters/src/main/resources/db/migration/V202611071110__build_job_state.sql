-- The once-only flag of the build job of a version (INV-T-22935; INV-AC-22934). The platform queue has no dedupe key, so the
-- primary key is the key build:<docUuid>:<version>; the row is also the lock the two fact stores take (select for update).
create table payload.build_job_state (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version integer not null check (version >= 1),
    job_enqueued boolean not null default false,
    primary key (tenant_id, doc_uuid, version)
);
ALTER TABLE payload.build_job_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE payload.build_job_state FORCE ROW LEVEL SECURITY;
create policy build_job_state_tenant_isolation on payload.build_job_state
    using      (tenant_id = (select payload.bound_tenant()))
    with check (tenant_id = (select payload.bound_tenant()));
grant usage on schema payload to payload_runtime;
grant select, insert, update on payload.build_job_state to payload_runtime;
