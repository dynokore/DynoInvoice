-- V202611061000__create_idempotency.sql (INV-T-4946; INV-REQ-1242, INV-Q-9649 A, API-15, TPL-12)
-- Generated: service-runtime's IdempotencySchema.ddl("masterdata", "masterdata_runtime", "masterdata_sys") of the BOM 0.112.0 train: the
-- Idempotency-Key store of the shared setPolicy for VP-29, VP-38 and VP-65. GeneratedMigrationsTest fails when this file
-- differs from what the pinned module generates. The binding functions are created before the migrations (TPL-12).
create table masterdata.idempotency_key (
    tenant_id uuid not null,
    principal text not null,
    idempotency_key uuid not null,
    fingerprint text not null,
    status integer not null check (status between 100 and 599),
    body text not null,
    created_at timestamptz not null,
    primary key (tenant_id, principal, idempotency_key)
);
grant usage on schema masterdata to masterdata_runtime;
grant select, insert on masterdata.idempotency_key to masterdata_runtime;
grant usage on schema masterdata to masterdata_sys;
ALTER TABLE masterdata.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_tenant_isolation ON masterdata.idempotency_key
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
create policy idempotency_key_sys_select on masterdata.idempotency_key for select to masterdata_sys using (true);
create policy idempotency_key_sys_purge on masterdata.idempotency_key for delete to masterdata_sys using (true);
grant select (tenant_id, principal, idempotency_key, created_at) on masterdata.idempotency_key to masterdata_sys;
grant delete on masterdata.idempotency_key to masterdata_sys;
create function masterdata.idempotency_key_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, masterdata, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '24 hours' then
        raise exception 'cut-off % is later than the retention floor', cutoff using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from masterdata.idempotency_key where (tenant_id, principal, idempotency_key) in
        (select tenant_id, principal, idempotency_key from masterdata.idempotency_key
         where created_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.idempotency_key_purge_expired(timestamptz, integer) owner to masterdata_sys;
revoke all on function masterdata.idempotency_key_purge_expired(timestamptz, integer) from public;
grant execute on function masterdata.idempotency_key_purge_expired(timestamptz, integer) to masterdata_runtime;
revoke create on schema masterdata from masterdata_sys;
