-- V202610210830__create_idempotency.sql: platform-generated DDL (TPL-12); regenerate with GeneratedMigrationsTest, never edit by hand.
-- Generated: service-runtime's IdempotencySchema.ddl("controlplane", "controlplane_runtime", "controlplane_sys") of the platform-libs-bom 0.103.0 train.
create table controlplane.idempotency_key (
    tenant_id uuid not null,
    principal text not null,
    idempotency_key uuid not null,
    fingerprint text not null,
    status integer not null check (status between 100 and 599),
    body text not null,
    created_at timestamptz not null,
    primary key (tenant_id, principal, idempotency_key)
);
grant usage on schema controlplane to controlplane_runtime;
grant select, insert on controlplane.idempotency_key to controlplane_runtime;
grant usage on schema controlplane to controlplane_sys;
ALTER TABLE controlplane.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE controlplane.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_tenant_isolation ON controlplane.idempotency_key
    USING      (tenant_id = (select controlplane.bound_tenant()))
    WITH CHECK (tenant_id = (select controlplane.bound_tenant()));
create policy idempotency_key_sys_select on controlplane.idempotency_key for select to controlplane_sys using (true);
create policy idempotency_key_sys_purge on controlplane.idempotency_key for delete to controlplane_sys using (true);
grant select (tenant_id, principal, idempotency_key, created_at) on controlplane.idempotency_key to controlplane_sys;
grant delete on controlplane.idempotency_key to controlplane_sys;
create function controlplane.idempotency_key_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, controlplane, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '24 hours' then
        raise exception 'cut-off % is later than the retention floor', cutoff using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from controlplane.idempotency_key where (tenant_id, principal, idempotency_key) in
        (select tenant_id, principal, idempotency_key from controlplane.idempotency_key
         where created_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema controlplane to controlplane_sys;
alter function controlplane.idempotency_key_purge_expired(timestamptz, integer) owner to controlplane_sys;
revoke all on function controlplane.idempotency_key_purge_expired(timestamptz, integer) from public;
grant execute on function controlplane.idempotency_key_purge_expired(timestamptz, integer) to controlplane_runtime;
revoke create on schema controlplane from controlplane_sys;
