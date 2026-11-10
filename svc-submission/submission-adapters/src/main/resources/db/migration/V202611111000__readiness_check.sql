SET lock_timeout = '5s';

-- INV-T-17045, INV-Q-17042 A, INV-REQ-951: the append-only record of the readiness checks of a period. The service that
-- produces the checks owns their storage. The runtime role may insert and select; it can never change or delete a record
-- (NIST SP 800-53 AU-9, AU-10).
create table submission.readiness_check (
    seq bigint generated always as identity primary key,
    tenant_id uuid not null default submission.bound_tenant(),
    entity_ref text not null,
    period_ref text not null,
    verdict text not null check (verdict in ('READY', 'NOT_READY')),
    checked_at timestamptz not null,
    approver text not null check (char_length(approver) >= 1),
    outcome_values jsonb null
);
comment on column submission.readiness_check.seq is 'class: operational';
comment on column submission.readiness_check.tenant_id is 'class: operational';
comment on column submission.readiness_check.entity_ref is 'class: operational';
comment on column submission.readiness_check.period_ref is 'class: operational';
comment on column submission.readiness_check.verdict is 'class: operational';
comment on column submission.readiness_check.checked_at is 'class: operational';
comment on column submission.readiness_check.approver is 'class: personal';
comment on column submission.readiness_check.outcome_values is 'class: content';

create index readiness_check_period_idx on submission.readiness_check (tenant_id, entity_ref, period_ref, seq desc);

alter table submission.readiness_check enable row level security;
alter table submission.readiness_check force row level security;
create policy readiness_check_tenant_isolation on submission.readiness_check
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.readiness_check to submission_runtime;
