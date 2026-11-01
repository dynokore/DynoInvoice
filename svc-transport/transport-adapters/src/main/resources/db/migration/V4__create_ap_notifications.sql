-- V4__create_ap_notifications.sql (INV-T-12484, slice 4 of the split of INV-T-4982; rulings INV-Q-9674 A, INV-Q-9684 A).
-- The de-duplication ledger of AP notifications (INV-AC-623), the owner lookup of a Doc UUID for a callback that
-- arrives with no tenant bound, and the poll cursor of a polling-only adapter.

-- One row per adapter and AP notification id. The kind check keeps INBOUND_NETWORK_DOCUMENT out (INV-Q-9676 A).
create table transport.ap_notification_ledger (
    adapter_id text not null check (char_length(adapter_id) between 1 and 40),
    ap_notification_id text not null check (char_length(ap_notification_id) between 1 and 100),
    tenant_id uuid not null,
    entity_ref text not null check (char_length(entity_ref) between 1 and 40),
    kind text not null check (kind in ('ACKNOWLEDGEMENT', 'DELIVERY_STATUS', 'REJECTION')),
    recorded_at timestamptz not null default now(),
    primary key (adapter_id, ap_notification_id)
);
alter table transport.ap_notification_ledger enable row level security;
alter table transport.ap_notification_ledger force row level security;
create policy ap_notification_ledger_tenant_isolation on transport.ap_notification_ledger
    using      (tenant_id = (select transport.bound_tenant()))
    with check (tenant_id = (select transport.bound_tenant()));
grant usage on schema transport to transport_runtime;
grant select, insert on transport.ap_notification_ledger to transport_runtime;

-- The poll position of an adapter: one row per adapter, platform data.
create table transport.ap_poll_cursor (
    adapter_id text primary key check (char_length(adapter_id) between 1 and 40),
    position text,
    updated_at timestamptz not null
);
comment on table transport.ap_poll_cursor is 'platform-scoped: no tenant data';
grant select, insert, update on transport.ap_poll_cursor to transport_runtime;

-- attempt_owner reads submission_attempt across tenants, as its owner, for the Doc UUID asked and nothing else:
-- the sys role sees only the columns the function reads. The result columns are not named tenant_id, so the generated
-- function is classified like adapter_attempt_counts: no tenant column, listed as platform-scoped in the bootstrap.
grant usage on schema transport to transport_sys;
create policy submission_attempt_sys_owner on transport.submission_attempt for select to transport_sys using (true);
grant select (id, tenant_id, entity_ref, doc_uuid, started_at) on transport.submission_attempt to transport_sys;
grant create on schema transport to transport_sys;
create function transport.attempt_owner(p_doc_uuid uuid)
returns table (owner_tenant uuid, owner_entity text)
language sql stable security definer set search_path = pg_catalog, transport, pg_temp as $$
    select a.tenant_id, a.entity_ref
    from transport.submission_attempt a
    where a.doc_uuid = p_doc_uuid
    order by a.started_at asc, a.id asc
    limit 1
$$;
alter function transport.attempt_owner(uuid) owner to transport_sys;
revoke all on function transport.attempt_owner(uuid) from public;
grant execute on function transport.attempt_owner(uuid) to transport_runtime;
revoke create on schema transport from transport_sys;
