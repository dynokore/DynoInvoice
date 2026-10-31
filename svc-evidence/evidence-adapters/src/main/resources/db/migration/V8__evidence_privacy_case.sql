-- V8__evidence_privacy_case.sql (INV-T-11705; INV-REQ-1403, INV-Q-6834 A, INV-Q-6836 A, INV-Q-6851 A, INV-Q-11688 A):
-- the privacy facts of a case, on the case_register of V4. All nine columns are nullable; the table check ties them to
-- the kind of the case. Policy, forced row-level security and the V4 grants stay.
alter table evidence.case_register
    add column request_kind text
        constraint case_register_request_kind_values check (request_kind in ('ACCESS', 'CORRECTION')),
    add column request_reference text
        constraint case_register_request_reference_length check (char_length(request_reference) between 1 and 100),
    add column received_on date,
    add column response_deadline date,
    add column deadline_period_days integer
        constraint case_register_deadline_period_days_min check (deadline_period_days >= 1),
    add column closed_at timestamptz,
    add column closure_outcome text
        constraint case_register_closure_outcome_values check (closure_outcome in ('ANSWERED', 'REFUSED')),
    add column closure_basis text
        constraint case_register_closure_basis_length check (char_length(closure_basis) between 1 and 500),
    add column notice_prompted_at timestamptz;

alter table evidence.case_register
    add constraint case_register_privacy_shape check (
        (case_kind = 'PRIVACY'
            and char_length(subject_ref) >= 1
            and request_kind is not null
            and request_reference is not null
            and received_on is not null
            and response_deadline is not null
            and deadline_period_days is not null)
        or
        (case_kind = 'SECURITY'
            and request_kind is null
            and request_reference is null
            and received_on is null
            and response_deadline is null
            and deadline_period_days is null
            and closed_at is null
            and closure_outcome is null
            and closure_basis is null
            and notice_prompted_at is null)
    ),
    add constraint case_register_closure_shape check (
        (closed_at is null and closure_outcome is null and closure_basis is null)
        or
        (closed_at is not null and closure_outcome is not null and closure_basis is not null)
    ),
    add constraint case_register_closure_iff_closed check (
        (closed_at is not null) = (status = 'CLOSED')
    );

create unique index case_register_request_reference_uq
    on evidence.case_register (tenant_id, request_reference);

-- Least privilege: the five columns a privacy case changes after opening. No UPDATE on the deadline, its period, the
-- receipt date, the kind, the reference or the subject, and no DELETE: the stored deadline cannot change.
grant update (status, closed_at, closure_outcome, closure_basis, notice_prompted_at)
    on evidence.case_register to evidence_runtime;
