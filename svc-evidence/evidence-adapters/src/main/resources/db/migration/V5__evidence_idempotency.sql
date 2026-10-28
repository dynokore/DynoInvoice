-- V5__evidence_idempotency.sql (INV-T-11693; API-15, TPL-12)
-- Generated: service-runtime's IdempotencySchema.ddl("evidence", "evidence_runtime", "evidence_sys") of the BOM train pinned in gradle/libs.versions.toml,
-- for the Idempotency-Key store of the evidence servlets. IdempotencyMigrationTest fails when this file differs from
-- what the pinned module generates. Run only by the migration Job as evidence_migrate.

create table evidence.idempotency_key (
    tenant_id uuid not null,
    principal text not null,
    idempotency_key uuid not null,
    fingerprint text not null,
    status integer not null check (status between 100 and 599),
    body text not null,
    created_at timestamptz not null,
    primary key (tenant_id, principal, idempotency_key)
);
grant usage on schema evidence to evidence_runtime;
grant select, insert on evidence.idempotency_key to evidence_runtime;
grant usage on schema evidence to evidence_sys;
ALTER TABLE evidence.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_tenant_isolation ON evidence.idempotency_key
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
create policy idempotency_key_sys_select on evidence.idempotency_key for select to evidence_sys using (true);
create policy idempotency_key_sys_purge on evidence.idempotency_key for delete to evidence_sys using (true);
grant select (tenant_id, principal, idempotency_key, created_at) on evidence.idempotency_key to evidence_sys;
grant delete on evidence.idempotency_key to evidence_sys;
create function evidence.idempotency_key_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '24 hours' then
        raise exception 'cut-off % is later than the retention floor', cutoff using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from evidence.idempotency_key where (tenant_id, principal, idempotency_key) in
        (select tenant_id, principal, idempotency_key from evidence.idempotency_key
         where created_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.idempotency_key_purge_expired(timestamptz, integer) owner to evidence_sys;
revoke all on function evidence.idempotency_key_purge_expired(timestamptz, integer) from public;
grant execute on function evidence.idempotency_key_purge_expired(timestamptz, integer) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
