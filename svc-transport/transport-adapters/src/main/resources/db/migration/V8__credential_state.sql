-- V8__credential_state.sql (INV-T-18676; INV-REQ-837, pattern of INV-REQ-880; ruling INV-Q-18656 A).
-- The credential state of an AP connection: REFERENCES and TIMES only. The secret itself lives in the vault and is read
-- by reference at call time (STK-29); no column holds a secret value. Tenant data, like ap_connection: a rotation is a
-- command of the tenant's own end user, so it runs under the tenant binding.

-- One row per tenant and adapter: the current reference, the previous one and the end of the overlap window while it is
-- open. A connection with no row uses the initial reference of its adapter, which is not stored.
create table transport.ap_credential_state (
    tenant_id uuid not null,
    adapter_id text not null check (char_length(adapter_id) between 1 and 40),
    current_ref text not null check (char_length(current_ref) between 1 and 100),
    previous_ref text check (char_length(previous_ref) between 1 and 100),
    overlap_end timestamptz,
    updated_at timestamptz not null,
    primary key (tenant_id, adapter_id),
    check ((previous_ref is null) = (overlap_end is null))
);
alter table transport.ap_credential_state enable row level security;
alter table transport.ap_credential_state force row level security;
create policy ap_credential_state_tenant_isolation on transport.ap_credential_state
    using      (tenant_id = (select transport.bound_tenant()))
    with check (tenant_id = (select transport.bound_tenant()));
grant usage on schema transport to transport_runtime;
grant select, insert, update on transport.ap_credential_state to transport_runtime;

-- The rotation log: one row per tenant, adapter and new reference, so a repeat returns the original outcome and the same
-- reference is not rotated in twice (INV-NOTE-7 section 7). The acting user ID, never a role (INV-Q-10647).
create table transport.ap_credential_rotation (
    tenant_id uuid not null,
    adapter_id text not null check (char_length(adapter_id) between 1 and 40),
    new_ref text not null check (char_length(new_ref) between 1 and 100),
    previous_ref text not null check (char_length(previous_ref) between 1 and 100),
    overlap_end timestamptz not null,
    rotated_at timestamptz not null,
    acting_user_id text not null check (char_length(acting_user_id) between 1 and 200),
    primary key (tenant_id, adapter_id, new_ref)
);
alter table transport.ap_credential_rotation enable row level security;
alter table transport.ap_credential_rotation force row level security;
create policy ap_credential_rotation_tenant_isolation on transport.ap_credential_rotation
    using      (tenant_id = (select transport.bound_tenant()))
    with check (tenant_id = (select transport.bound_tenant()));
grant select, insert on transport.ap_credential_rotation to transport_runtime;
