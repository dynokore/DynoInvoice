-- The replica of the MDE pack versions payload-svc holds from RulePackStaged and RulePackActivated (INV-T-23579; INV-AC-23578). Event-carried state transfer: a version is staged once with its contents, which are never rewritten; exactly one version of a tenant is active. Expand-only.
create table payload.mde_pack_replica (
    tenant_id uuid not null,
    pack_version text not null check (pack_version <> ''),
    contents jsonb not null,
    staged_at timestamptz not null default now(),
    active boolean not null default false,
    primary key (tenant_id, pack_version)
);
ALTER TABLE payload.mde_pack_replica ENABLE ROW LEVEL SECURITY;
ALTER TABLE payload.mde_pack_replica FORCE ROW LEVEL SECURITY;
create policy mde_pack_replica_tenant_isolation on payload.mde_pack_replica
    using      (tenant_id = (select payload.bound_tenant()))
    with check (tenant_id = (select payload.bound_tenant()));
grant usage on schema payload to payload_runtime;
-- the activation flag is the only column that changes after the insert: the contents of a staged version are never rewritten
grant select, insert on payload.mde_pack_replica to payload_runtime;
grant update (active) on payload.mde_pack_replica to payload_runtime;
