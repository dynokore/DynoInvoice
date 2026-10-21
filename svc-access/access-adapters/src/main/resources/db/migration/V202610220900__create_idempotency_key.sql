-- V202610220900__create_idempotency_key.sql (INV-T-4935; API-15, TPL-12)
-- Generated: service-runtime's IdempotencySchema.ddl("access", "access_runtime", "access_sys") of the BOM train pinned in gradle/libs.versions.toml,
-- for the Idempotency-Key store of the setPolicy servlet. GeneratedMigrationsTest fails when this file differs from
-- what the pinned module generates. Run only by the migration Job as access_migrate.

create table access.idempotency_key (
    tenant_id uuid not null,
    principal text not null,
    idempotency_key uuid not null,
    fingerprint text not null,
    status integer not null check (status between 100 and 599),
    body text not null,
    created_at timestamptz not null,
    primary key (tenant_id, principal, idempotency_key)
);
grant usage on schema access to access_runtime;
grant select, insert on access.idempotency_key to access_runtime;
grant usage on schema access to access_sys;
ALTER TABLE access.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_tenant_isolation ON access.idempotency_key
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));
create policy idempotency_key_sys_select on access.idempotency_key for select to access_sys using (true);
create policy idempotency_key_sys_purge on access.idempotency_key for delete to access_sys using (true);
grant select (tenant_id, principal, idempotency_key, created_at) on access.idempotency_key to access_sys;
grant delete on access.idempotency_key to access_sys;
create function access.idempotency_key_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, access, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '24 hours' then
        raise exception 'cut-off % is later than the retention floor', cutoff using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from access.idempotency_key where (tenant_id, principal, idempotency_key) in
        (select tenant_id, principal, idempotency_key from access.idempotency_key
         where created_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema access to access_sys;
alter function access.idempotency_key_purge_expired(timestamptz, integer) owner to access_sys;
revoke all on function access.idempotency_key_purge_expired(timestamptz, integer) from public;
grant execute on function access.idempotency_key_purge_expired(timestamptz, integer) to access_runtime;
revoke create on schema access from access_sys;
