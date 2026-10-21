-- V1__evidence_schema.sql (TPL-12; INV-T-5547).
--
-- Roles (INV-AC-2175): evidence_migrate owns the tables and runs this file; evidence_runtime logs in;
-- evidence_sys is NOLOGIN. No role named "evidence" exists.
-- Precondition (coordinator rulings INV-Q-6586 and INV-Q-6663): evidence.bound_tenant() and evidence.bound_actor()
-- already exist in the schema, installed once per schema by the sys login from tenancy-kernel's
-- BindingKeyMigration.ddl; this file does not run that generator and does not switch role.
-- The schema itself is created by Flyway (the `schemas` setting) or by the installer, owned by evidence_migrate.
grant usage on schema evidence to evidence_runtime;

-- OutboxSchema.ddl("evidence", "evidence_runtime", "evidence_sys")
create table evidence.outbox (
    seq bigint generated always as identity primary key,
    id text not null unique,
    topic text not null,
    type text not null,
    source text not null,
    subject text not null,
    event_time timestamptz not null,
    tenant_id uuid not null,
    entity_ref text not null,
    doc_uuid uuid,
    transaction_date date,
    docsequence bigint,
    instance_id text not null,
    correlation_id text not null,
    causation_id text not null,
    data jsonb not null,
    published_at timestamptz
);
create index outbox_unpublished on evidence.outbox (seq) where published_at is null;
create index outbox_published_at on evidence.outbox (published_at) where published_at is not null;
create table evidence.outbox_sequence (
    tenant_id uuid not null,
    aggregate_key text not null,
    last_value bigint not null,
    primary key (tenant_id, aggregate_key)
);
comment on column evidence.outbox.data is 'class: content';
ALTER TABLE evidence.outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_tenant_isolation ON evidence.outbox
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
ALTER TABLE evidence.outbox_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.outbox_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_sequence_tenant_isolation ON evidence.outbox_sequence
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
create function evidence.outbox_publish_once() returns trigger
language plpgsql set search_path = pg_catalog, evidence, pg_temp as $$
begin
    if (to_jsonb(new) - 'published_at') is distinct from (to_jsonb(old) - 'published_at') then
        raise exception 'an outbox row is never rewritten: only published_at is set, once'
            using errcode = '42501';
    end if;
    if old.published_at is not null then
        raise exception 'outbox row % is published: published_at is never re-set, cleared or backdated',
            old.seq using errcode = '42501';
    end if;
    if new.published_at is null then
        raise exception 'published_at changes only from NULL to the database time' using errcode = '42501';
    end if;
    new.published_at := clock_timestamp();
    return new;
end
$$;
revoke all on function evidence.outbox_publish_once() from public;
create trigger outbox_publish_once before update on evidence.outbox
    for each row execute function evidence.outbox_publish_once();
grant usage on schema evidence to evidence_runtime;
grant select, insert, update (published_at) on evidence.outbox to evidence_runtime;
grant select, insert, update on evidence.outbox_sequence to evidence_runtime;
grant usage on schema evidence to evidence_sys;
create policy outbox_sys_select on evidence.outbox for select to evidence_sys using (true);
create policy outbox_sys_purge on evidence.outbox for delete to evidence_sys using (published_at is not null);
create policy outbox_sys_purge_published on evidence.outbox as restrictive for delete to evidence_sys
    using (published_at is not null);
grant select (seq, tenant_id, published_at) on evidence.outbox to evidence_sys;
grant delete on evidence.outbox to evidence_sys;
grant insert on evidence.outbox to evidence_sys;
create view evidence.outbox_claim_v as select seq, tenant_id, published_at from evidence.outbox;
grant create on schema evidence to evidence_sys;
alter view evidence.outbox_claim_v owner to evidence_sys;
revoke all on evidence.outbox_claim_v from public;
grant select on evidence.outbox_claim_v to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.outbox_claim_page(after_seq bigint, page_size integer, unpublished_only boolean)
returns table (seq bigint, tenant_id uuid, published_at timestamptz)
language plpgsql stable security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    return query select v.seq, v.tenant_id, v.published_at from evidence.outbox_claim_v v
        where v.seq > after_seq and (not unpublished_only or v.published_at is null)
        order by v.seq limit page_size;
end
$$;
create function evidence.outbox_claim_pending() returns bigint
language plpgsql stable security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    return (select count(*) from evidence.outbox_claim_v v where v.published_at is null);
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.outbox_claim_page(bigint, integer, boolean) owner to evidence_sys;
revoke all on function evidence.outbox_claim_page(bigint, integer, boolean) from public;
grant execute on function evidence.outbox_claim_page(bigint, integer, boolean) to evidence_runtime;
alter function evidence.outbox_claim_pending() owner to evidence_sys;
revoke all on function evidence.outbox_claim_pending() from public;
grant execute on function evidence.outbox_claim_pending() to evidence_runtime;
revoke create on schema evidence from evidence_sys;
create function evidence.outbox_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '7 days' then
        raise exception 'cut-off % is later than the retention floor of 7 days', cutoff
            using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from evidence.outbox where seq in
        (select seq from evidence.outbox where published_at is not null and published_at < cutoff
         limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.outbox_purge_expired(timestamptz, integer) owner to evidence_sys;
revoke all on function evidence.outbox_purge_expired(timestamptz, integer) from public;
grant execute on function evidence.outbox_purge_expired(timestamptz, integer) to evidence_runtime;
revoke create on schema evidence from evidence_sys;
