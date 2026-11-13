SET lock_timeout = '5s';

-- INV-T-21089, INV-REQ-79, INV-AC-21088: the append-only record of the codes raised for a document version. One row
-- per (tenant, doc_uuid, version, code); the runtime role may only select and insert (NIST SP 800-53 AU-9).
-- Additive: this migration creates one table and alters nothing.
create table submission.raised_code (
    seq bigint generated always as identity primary key,
    tenant_id uuid not null,
    doc_uuid text not null,
    version integer not null check (version >= 1),
    entity_ref text not null check (btrim(entity_ref) <> ''),
    period_ref text not null check (btrim(period_ref) <> ''),
    code text not null check (btrim(code) <> ''),
    raised_at timestamptz not null,
    unique (tenant_id, doc_uuid, version, code)
);

comment on column submission.raised_code.seq is 'class: operational';
comment on column submission.raised_code.tenant_id is 'class: operational';
comment on column submission.raised_code.doc_uuid is 'class: operational';
comment on column submission.raised_code.version is 'class: operational';
comment on column submission.raised_code.entity_ref is 'class: operational';
comment on column submission.raised_code.period_ref is 'class: operational';
comment on column submission.raised_code.code is 'class: operational';
comment on column submission.raised_code.raised_at is 'class: operational';

alter table submission.raised_code enable row level security;
alter table submission.raised_code force row level security;
create policy raised_code_tenant_isolation on submission.raised_code
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.raised_code to submission_runtime;
