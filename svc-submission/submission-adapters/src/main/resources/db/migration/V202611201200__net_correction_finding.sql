SET lock_timeout = '5s';

-- INV-T-25531, INV-REQ-558 behavior 6 (owner option A, 2026-11-20): the findings of the net correction path. One row per
-- (kind, credit note, version) of a tenant; the unique key treats a missing version as equal to a missing version, so a
-- repeated NET_NOT_POSITIVE finding of a credit note stores once. recorded_by is the acting service identity of the
-- event consumer, never a role (INV-Q-10647). Pattern: V202611201110__netting_declaration (tenant column, forced RLS).
create table submission.net_correction_finding (
    tenant_id uuid not null,
    finding_id uuid not null default gen_random_uuid() primary key,
    kind text not null,
    credit_note_doc_uuid text not null,
    credit_note_version integer null check (credit_note_version >= 1),
    original_doc_uuid text null,
    recorded_at timestamptz not null default now(),
    recorded_by text not null check (char_length(btrim(recorded_by)) >= 1),
    constraint net_correction_finding_kind_check check (kind in ('AMENDED_AFTER_TRANSMISSION', 'NET_NOT_POSITIVE')),
    constraint net_correction_finding_key
        unique nulls not distinct (tenant_id, kind, credit_note_doc_uuid, credit_note_version)
);

alter table submission.net_correction_finding enable row level security;
alter table submission.net_correction_finding force row level security;
create policy net_correction_finding_tenant_isolation on submission.net_correction_finding
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.net_correction_finding to submission_runtime;

comment on column submission.net_correction_finding.tenant_id is 'class: operational';
comment on column submission.net_correction_finding.finding_id is 'class: operational';
comment on column submission.net_correction_finding.kind is 'class: operational';
comment on column submission.net_correction_finding.credit_note_doc_uuid is 'class: operational';
comment on column submission.net_correction_finding.credit_note_version is 'class: operational';
comment on column submission.net_correction_finding.original_doc_uuid is 'class: operational';
comment on column submission.net_correction_finding.recorded_at is 'class: operational';
comment on column submission.net_correction_finding.recorded_by is 'class: operational';
