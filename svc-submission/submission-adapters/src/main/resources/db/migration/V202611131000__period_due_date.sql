SET lock_timeout = '5s';

-- INV-T-20514, INV-REQ-570: the filing due date of a period as the PeriodDueDateChanged feed last set it. A row
-- changes only for a higher event sequence, so a re-delivery or an older event changes nothing.
create table submission.period_due_date (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    filing_due_date date not null,
    event_sequence bigint not null,
    primary key (tenant_id, entity_ref, period_ref)
);

alter table submission.period_due_date enable row level security;
alter table submission.period_due_date force row level security;
create policy period_due_date_tenant_isolation on submission.period_due_date
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.period_due_date to submission_runtime;
