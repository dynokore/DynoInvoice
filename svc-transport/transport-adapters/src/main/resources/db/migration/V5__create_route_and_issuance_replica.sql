-- V5__create_route_and_issuance_replica.sql (INV-T-12485, slice 5 of the split of INV-T-4982; rulings INV-Q-9668,
-- INV-Q-9678 A, INV-Q-4872 A, INV-Q-4993 B).
-- The elected acknowledgement route of an AP connection (INV-REQ-1310), the entity refs assigned to it, and the route
-- version counter, which is its own column: ap_connection.version is raised by a ceiling save, which is no route change.
alter table transport.ap_connection
    add column acknowledgement_route text
        constraint ap_connection_acknowledgement_route_check
            check (acknowledgement_route in ('AP_CALLBACK', 'POLLING', 'REPORT_IMPORT')),
    add column entity_refs text[] not null default '{}',
    add column route_version bigint not null default 0
        constraint ap_connection_route_version_check check (route_version >= 0);

-- The issuanceMode replica (EntityProfileChanged): one row per tenant and entity, holding the issuanceMode entries
-- (value and effectiveDate) and the structureVersion, and no other attribute of the profile.
create table transport.issuance_mode_replica (
    tenant_id uuid not null,
    entity_ref text not null check (char_length(entity_ref) between 1 and 40),
    entries jsonb not null check (jsonb_typeof(entries) = 'array'),
    structure_version bigint not null check (structure_version >= 0),
    primary key (tenant_id, entity_ref)
);
alter table transport.issuance_mode_replica enable row level security;
alter table transport.issuance_mode_replica force row level security;
create policy issuance_mode_replica_tenant_isolation on transport.issuance_mode_replica
    using      (tenant_id = (select transport.bound_tenant()))
    with check (tenant_id = (select transport.bound_tenant()));
grant usage on schema transport to transport_runtime;
grant select, insert, update on transport.issuance_mode_replica to transport_runtime;
