SET lock_timeout = '5s';

-- INV-T-22286, INV-REQ-978, INV-AC-22283: the structure nodes (ENTITY, GROUP) of a tenant, kept from the tenancy
-- structure events (event-carried state transfer). Only kind, parent, GSTN and version are kept (data minimisation).
create table submission.entity_node (
    tenant_id uuid not null,
    node_ref text not null check (char_length(btrim(node_ref)) between 1 and 200),
    kind text not null check (kind in ('ENTITY', 'GROUP')),
    parent_ref text null,
    gstn text null check (gstn is null or char_length(btrim(gstn)) between 1 and 32),
    structure_version bigint not null check (structure_version >= 1),
    primary key (tenant_id, node_ref)
);

alter table submission.entity_node enable row level security;
alter table submission.entity_node force row level security;
create policy entity_node_tenant_isolation on submission.entity_node
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.entity_node to submission_runtime;

comment on column submission.entity_node.tenant_id is 'class: operational';
comment on column submission.entity_node.node_ref is 'class: operational';
comment on column submission.entity_node.kind is 'class: operational';
comment on column submission.entity_node.parent_ref is 'class: operational';
comment on column submission.entity_node.gstn is 'class: operational';
comment on column submission.entity_node.structure_version is 'class: operational';
