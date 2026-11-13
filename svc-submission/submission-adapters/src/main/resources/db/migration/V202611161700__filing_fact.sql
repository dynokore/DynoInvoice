SET lock_timeout = '5s';

-- INV-T-20626, INV-REQ-917: the append-only filing facts of a period. The runtime role may insert and select; it can
-- never change or delete a fact (NIST SP 800-53 AU-9). recorded_by is the acting user id, never a role (INV-Q-10647).
-- The unique key makes an identical fact append once.
create table submission.filing_fact (
    seq bigint generated always as identity primary key,
    tenant_id uuid not null,
    entity_ref text not null check (char_length(btrim(entity_ref)) >= 1),
    period_ref text not null check (char_length(btrim(period_ref)) >= 1),
    filed_at date not null,
    acknowledgement_ref text not null check (char_length(btrim(acknowledgement_ref)) >= 1),
    portal_ref text null,
    is_correction boolean not null default false,
    recorded_by text not null check (char_length(btrim(recorded_by)) >= 1),
    recorded_at timestamptz not null,
    constraint filing_fact_identical_key
        unique (tenant_id, entity_ref, period_ref, filed_at, acknowledgement_ref, is_correction)
);
comment on column submission.filing_fact.seq is 'class: operational';
comment on column submission.filing_fact.tenant_id is 'class: operational';
comment on column submission.filing_fact.entity_ref is 'class: operational';
comment on column submission.filing_fact.period_ref is 'class: operational';
comment on column submission.filing_fact.filed_at is 'class: operational';
comment on column submission.filing_fact.acknowledgement_ref is 'class: operational';
comment on column submission.filing_fact.portal_ref is 'class: operational';
comment on column submission.filing_fact.is_correction is 'class: operational';
comment on column submission.filing_fact.recorded_by is 'class: personal';
comment on column submission.filing_fact.recorded_at is 'class: operational';

alter table submission.filing_fact enable row level security;
alter table submission.filing_fact force row level security;
create policy filing_fact_tenant_isolation on submission.filing_fact
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.filing_fact to submission_runtime;
