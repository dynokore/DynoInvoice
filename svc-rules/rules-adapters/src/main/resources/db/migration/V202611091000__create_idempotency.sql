-- V202611091000__create_idempotency.sql (INV-T-18451; INV-REQ-1239, INV-Q-8972 A, API-15, TPL-12)
-- Generated: service-runtime's IdempotencySchema.ddl("rules", "rules_runtime", "rules_sys") of the pinned BOM: the
-- Idempotency-Key store of the shared setPolicy for VP-39. IdempotencyConfigMigrationsTest fails when this file differs
-- from what the pinned module generates. The binding functions are created before the migrations (TPL-12).
create table rules.idempotency_key (
    tenant_id uuid not null,
    principal text not null,
    idempotency_key uuid not null,
    fingerprint text not null,
    status integer not null check (status between 100 and 599),
    body text not null,
    created_at timestamptz not null,
    primary key (tenant_id, principal, idempotency_key)
);
grant usage on schema rules to rules_runtime;
grant select, insert on rules.idempotency_key to rules_runtime;
grant usage on schema rules to rules_sys;
ALTER TABLE rules.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE rules.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_tenant_isolation ON rules.idempotency_key
    USING      (tenant_id = (select rules.bound_tenant()))
    WITH CHECK (tenant_id = (select rules.bound_tenant()));
create policy idempotency_key_sys_select on rules.idempotency_key for select to rules_sys using (true);
create policy idempotency_key_sys_purge on rules.idempotency_key for delete to rules_sys using (true);
grant select (tenant_id, principal, idempotency_key, created_at) on rules.idempotency_key to rules_sys;
grant delete on rules.idempotency_key to rules_sys;
create function rules.idempotency_key_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, rules, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '24 hours' then
        raise exception 'cut-off % is later than the retention floor', cutoff using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from rules.idempotency_key where (tenant_id, principal, idempotency_key) in
        (select tenant_id, principal, idempotency_key from rules.idempotency_key
         where created_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema rules to rules_sys;
alter function rules.idempotency_key_purge_expired(timestamptz, integer) owner to rules_sys;
revoke all on function rules.idempotency_key_purge_expired(timestamptz, integer) from public;
grant execute on function rules.idempotency_key_purge_expired(timestamptz, integer) to rules_runtime;
revoke create on schema rules from rules_sys;
