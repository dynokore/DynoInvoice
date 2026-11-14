SET lock_timeout = '5s';

-- INV-T-10714, INV-Q-10691 A, INV-REQ-1293: one override per (tenant, entity, period, check) in the table of
-- INV-T-20557. Nothing else of submission.filing_override changes. No production row exists before go-live, so the
-- index cannot meet a duplicate; a duplicate would fail the migration and nothing would be deleted (fail closed).
create unique index filing_override_one_per_check
    on submission.filing_override (tenant_id, entity_ref, period_ref, check_id);

-- INV-T-10714, NIST SP 800-92, SP 800-53 AU-2, AU-3, AU-9: the audit entries of the filing-gate overrides. The runtime
-- role may insert and select; it can never change or delete an entry. actor_user_id is a user ID, never a role
-- (INV-Q-10647).
create table submission.filing_gate_override_audit (
    seq bigint generated always as identity primary key,
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    check_id text not null,
    action text not null check (action in ('REQUESTED', 'APPROVED', 'RELEASED')),
    actor_user_id text not null check (char_length(actor_user_id) between 1 and 64),
    first_approver_user_id text null check (char_length(first_approver_user_id) between 1 and 64),
    reason text null check (char_length(reason) between 1 and 1000),
    recorded_at timestamptz not null default now(),
    check ((action = 'REQUESTED') = (first_approver_user_id is null)),
    check ((action = 'REQUESTED') = (reason is not null))
);
comment on column submission.filing_gate_override_audit.seq is 'class: operational';
comment on column submission.filing_gate_override_audit.tenant_id is 'class: operational';
comment on column submission.filing_gate_override_audit.entity_ref is 'class: operational';
comment on column submission.filing_gate_override_audit.period_ref is 'class: operational';
comment on column submission.filing_gate_override_audit.check_id is 'class: operational';
comment on column submission.filing_gate_override_audit.action is 'class: operational';
comment on column submission.filing_gate_override_audit.actor_user_id is 'class: personal';
comment on column submission.filing_gate_override_audit.first_approver_user_id is 'class: personal';
comment on column submission.filing_gate_override_audit.reason is 'class: content';
comment on column submission.filing_gate_override_audit.recorded_at is 'class: operational';

create index filing_gate_override_audit_period_idx
    on submission.filing_gate_override_audit (tenant_id, entity_ref, period_ref, seq);

alter table submission.filing_gate_override_audit enable row level security;
alter table submission.filing_gate_override_audit force row level security;
create policy filing_gate_override_audit_tenant_isolation on submission.filing_gate_override_audit
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.filing_gate_override_audit to submission_runtime;
