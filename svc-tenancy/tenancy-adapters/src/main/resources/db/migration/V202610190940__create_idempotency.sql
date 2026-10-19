-- V202610190940__create_idempotency.sql (INV-T-4925; API-15, TPL-12)
-- Generated: service-runtime's IdempotencySchema.ddl("tenancy", "tenancy_runtime", "tenancy_sys") of the BOM 0.97.0 train.
-- GeneratedMigrationsTest fails when this file differs from what the pinned module generates.
create table tenancy.idempotency_key (
    tenant_id uuid not null,
    principal text not null,
    idempotency_key uuid not null,
    fingerprint text not null,
    status integer not null check (status between 100 and 599),
    body text not null,
    created_at timestamptz not null,
    primary key (tenant_id, principal, idempotency_key)
);
grant usage on schema tenancy to tenancy_runtime;
grant select, insert on tenancy.idempotency_key to tenancy_runtime;
grant usage on schema tenancy to tenancy_sys;
ALTER TABLE tenancy.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_tenant_isolation ON tenancy.idempotency_key
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));
create policy idempotency_key_sys_select on tenancy.idempotency_key for select to tenancy_sys using (true);
create policy idempotency_key_sys_purge on tenancy.idempotency_key for delete to tenancy_sys using (true);
grant select (tenant_id, principal, idempotency_key, created_at) on tenancy.idempotency_key to tenancy_sys;
grant delete on tenancy.idempotency_key to tenancy_sys;
create function tenancy.idempotency_key_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, tenancy, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '24 hours' then
        raise exception 'cut-off % is later than the retention floor', cutoff using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from tenancy.idempotency_key where (tenant_id, principal, idempotency_key) in
        (select tenant_id, principal, idempotency_key from tenancy.idempotency_key
         where created_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema tenancy to tenancy_sys;
alter function tenancy.idempotency_key_purge_expired(timestamptz, integer) owner to tenancy_sys;
revoke all on function tenancy.idempotency_key_purge_expired(timestamptz, integer) from public;
grant execute on function tenancy.idempotency_key_purge_expired(timestamptz, integer) to tenancy_runtime;
revoke create on schema tenancy from tenancy_sys;
