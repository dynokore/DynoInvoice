SET lock_timeout = '5s';

-- INV-T-23668, INV-REQ-2467, INV-AC-23662: the replica of the Access Point connection an entity is assigned to, kept
-- from AccessPointRouteChanged (event-carried state transfer; transport-svc owns the assignment). Versions are per
-- connection: ap_connection_version holds the newest version applied for each connection, and
-- ap_connection_entity.connection_version is the version of the connection event that stored the row.
create table submission.ap_connection_version (
    tenant_id uuid not null,
    connection_ref text not null check (char_length(btrim(connection_ref)) between 1 and 40),
    version bigint not null check (version >= 1),
    primary key (tenant_id, connection_ref)
);

create table submission.ap_connection_entity (
    tenant_id uuid not null,
    entity_ref text not null check (char_length(btrim(entity_ref)) between 1 and 40),
    connection_ref text not null check (char_length(btrim(connection_ref)) between 1 and 40),
    connection_version bigint not null check (connection_version >= 1),
    primary key (tenant_id, entity_ref)
);

create index ap_connection_entity_connection_idx on submission.ap_connection_entity (tenant_id, connection_ref);

alter table submission.ap_connection_version enable row level security;
alter table submission.ap_connection_version force row level security;
create policy ap_connection_version_tenant_isolation on submission.ap_connection_version
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.ap_connection_version to submission_runtime;

alter table submission.ap_connection_entity enable row level security;
alter table submission.ap_connection_entity force row level security;
create policy ap_connection_entity_tenant_isolation on submission.ap_connection_entity
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update, delete on submission.ap_connection_entity to submission_runtime;

comment on column submission.ap_connection_version.tenant_id is 'class: operational';
comment on column submission.ap_connection_version.connection_ref is 'class: operational';
comment on column submission.ap_connection_version.version is 'class: operational';
comment on column submission.ap_connection_entity.tenant_id is 'class: operational';
comment on column submission.ap_connection_entity.entity_ref is 'class: operational';
comment on column submission.ap_connection_entity.connection_ref is 'class: operational';
comment on column submission.ap_connection_entity.connection_version is 'class: operational';
