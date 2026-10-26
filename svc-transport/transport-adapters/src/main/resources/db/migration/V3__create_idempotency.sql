-- V3__create_idempotency.sql (INV-T-9689, INV-Q-9684 option A).
-- The Idempotency-Key store of service-runtime (API-15): IdempotencySchema.ddl("transport", "transport_runtime", "transport_sys").
-- GeneratedMigrationsTest compares the text below with that call; regenerate with -Dtransport.regenerate=true.
create table transport.idempotency_key (
    tenant_id uuid not null,
    principal text not null,
    idempotency_key uuid not null,
    fingerprint text not null,
    status integer not null check (status between 100 and 599),
    body text not null,
    created_at timestamptz not null,
    primary key (tenant_id, principal, idempotency_key)
);
grant usage on schema transport to transport_runtime;
grant select, insert on transport.idempotency_key to transport_runtime;
grant usage on schema transport to transport_sys;
ALTER TABLE transport.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_tenant_isolation ON transport.idempotency_key
    USING      (tenant_id = (select transport.bound_tenant()))
    WITH CHECK (tenant_id = (select transport.bound_tenant()));
create policy idempotency_key_sys_select on transport.idempotency_key for select to transport_sys using (true);
create policy idempotency_key_sys_purge on transport.idempotency_key for delete to transport_sys using (true);
grant select (tenant_id, principal, idempotency_key, created_at) on transport.idempotency_key to transport_sys;
grant delete on transport.idempotency_key to transport_sys;
create function transport.idempotency_key_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, transport, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '24 hours' then
        raise exception 'cut-off % is later than the retention floor', cutoff using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from transport.idempotency_key where (tenant_id, principal, idempotency_key) in
        (select tenant_id, principal, idempotency_key from transport.idempotency_key
         where created_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema transport to transport_sys;
alter function transport.idempotency_key_purge_expired(timestamptz, integer) owner to transport_sys;
revoke all on function transport.idempotency_key_purge_expired(timestamptz, integer) from public;
grant execute on function transport.idempotency_key_purge_expired(timestamptz, integer) to transport_runtime;
revoke create on schema transport from transport_sys;
