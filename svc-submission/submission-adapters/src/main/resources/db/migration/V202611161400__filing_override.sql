SET lock_timeout = '5s';

-- INV-T-20557, INV-REQ-72, INV-AC-20556: the stored overrides of the filing gate, one row per override, with the acting
-- user ids (INV-Q-10647). Expand step: one new table; nothing is dropped or altered. Rows are never deleted.
create table submission.filing_override (
    tenant_id uuid not null,
    id text not null,
    entity_ref text not null,
    period_ref text not null,
    check_id text not null,
    reason text not null,
    status text not null check (status in ('PENDING', 'RELEASED')),
    requested_by text not null,
    released_by text null,
    primary key (tenant_id, id),
    check ((status = 'RELEASED') = (released_by is not null))
);
create index filing_override_entity_period on submission.filing_override (tenant_id, entity_ref, period_ref);

alter table submission.filing_override enable row level security;
alter table submission.filing_override force row level security;
create policy filing_override_tenant_isolation on submission.filing_override
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.filing_override to submission_runtime;

comment on column submission.filing_override.tenant_id is 'class: operational';
comment on column submission.filing_override.id is 'class: operational';
comment on column submission.filing_override.entity_ref is 'class: operational';
comment on column submission.filing_override.period_ref is 'class: operational';
comment on column submission.filing_override.check_id is 'class: operational';
comment on column submission.filing_override.reason is 'class: operational';
comment on column submission.filing_override.status is 'class: operational';
comment on column submission.filing_override.requested_by is 'class: personal';
comment on column submission.filing_override.released_by is 'class: personal';
