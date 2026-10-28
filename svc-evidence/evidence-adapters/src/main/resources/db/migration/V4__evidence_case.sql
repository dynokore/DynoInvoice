-- V4__evidence_case.sql (INV-T-11693; INV-Q-11680 A, INV-REQ-880): the case register of evidence-svc.
-- One row per privacy or security case, owned by this schema, under forced tenant isolation exactly like V1.
-- Least privilege: evidence_runtime may SELECT and INSERT; INV-T-6842 adds its own grant for the columns it writes.
create table evidence.case_register (
    tenant_id uuid not null,
    case_id uuid not null,
    case_kind text not null check (case_kind in ('PRIVACY', 'SECURITY')),
    status text not null check (status in ('OPEN', 'EXPORT_READY', 'CLOSED')),
    subject_ref text check (char_length(subject_ref) <= 60),
    opened_at timestamptz not null,
    review_reference text check (char_length(review_reference) <= 100),
    audit_log_entry_ids uuid[],
    outcome text check (char_length(outcome) <= 200),
    breach_assessment_opened boolean,
    primary key (tenant_id, case_id),
    constraint case_register_kind_shape check (
        (case_kind = 'SECURITY'
            and subject_ref is null
            and status = 'OPEN'
            and review_reference is not null
            and outcome is not null
            and breach_assessment_opened is not null
            and cardinality(audit_log_entry_ids) >= 1)
        or
        (case_kind = 'PRIVACY'
            and subject_ref is not null
            and review_reference is null
            and audit_log_entry_ids is null
            and outcome is null
            and breach_assessment_opened is null)
    )
);
ALTER TABLE evidence.case_register ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.case_register FORCE ROW LEVEL SECURITY;
CREATE POLICY case_register_tenant_isolation ON evidence.case_register
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
grant select, insert on evidence.case_register to evidence_runtime;
