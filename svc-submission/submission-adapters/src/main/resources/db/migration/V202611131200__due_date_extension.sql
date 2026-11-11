SET lock_timeout = '5s';

-- INV-T-20596, INV-REQ-81, INV-REQ-46: the recorded IRAS due-date extensions consumed from PeriodExtended. A row is
-- written once and never rewritten (submission_runtime holds SELECT and INSERT only). original_due_date is the
-- register due date read at consume time, null when no entry of the scope exists or the scope is a date range.
create table submission.due_date_extension (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    target text not null check (target in ('FILING_DUE_DATE', 'SUBMISSION_DUE_DATE', 'BOTH')),
    scope_kind text not null check (scope_kind in ('PERIOD', 'DOCUMENT_SET', 'DATE_RANGE')),
    scope_ref text,
    original_due_date date,
    extended_due_date date not null,
    extended_filing_due_date date,
    iras_reference text not null,
    source_event_id text not null,
    recorded_at timestamptz not null default clock_timestamp(),
    constraint due_date_extension_event unique (tenant_id, source_event_id),
    constraint due_date_extension_scope_ref check ((scope_kind = 'PERIOD') = (scope_ref is null))
);
create index due_date_extension_entity_period on submission.due_date_extension (entity_ref, period_ref);

alter table submission.due_date_extension enable row level security;
alter table submission.due_date_extension force row level security;
create policy due_date_extension_tenant_isolation on submission.due_date_extension
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.due_date_extension to submission_runtime;
