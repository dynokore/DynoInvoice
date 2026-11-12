SET lock_timeout = '5s';

-- INV-T-20679, INV-REQ-570: the period records in force of an entity as the PeriodRecordsChanged feed last set them
-- (event-carried state transfer). An event replaces the entity's rows as a set; a row changes only for a higher
-- event version, which the consumer checks.
create table submission.period_record (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    period_start date not null,
    period_end date not null check (period_end >= period_start),
    version integer not null check (version >= 1),
    primary key (tenant_id, entity_ref, period_ref)
);

alter table submission.period_record enable row level security;
alter table submission.period_record force row level security;
create policy period_record_tenant_isolation on submission.period_record
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update, delete on submission.period_record to submission_runtime;
