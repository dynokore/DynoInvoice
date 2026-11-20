SET lock_timeout = '5s';

-- INV-T-25354, INV-REQ-558 behavior 6: the netting an ERP revision of a purchase invoice declares. One row per
-- (revised document, credit note) pair, written when the revision's canonical version is consumed and replaced as a
-- set on every later revision of the same document. Read by `declared(creditNoteDocUuid)` of the correction path.
create table submission.netting_declaration (
    tenant_id uuid not null,
    revised_doc_uuid text not null,
    credit_note_doc_uuid text not null,
    declared_at timestamptz not null default now(),
    primary key (tenant_id, revised_doc_uuid, credit_note_doc_uuid)
);
create index netting_declaration_credit_note_idx on submission.netting_declaration (tenant_id, credit_note_doc_uuid);

alter table submission.netting_declaration enable row level security;
alter table submission.netting_declaration force row level security;
create policy netting_declaration_tenant_isolation on submission.netting_declaration
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, delete on submission.netting_declaration to submission_runtime;

comment on column submission.netting_declaration.tenant_id is 'class: operational';
comment on column submission.netting_declaration.revised_doc_uuid is 'class: operational';
comment on column submission.netting_declaration.credit_note_doc_uuid is 'class: operational';
comment on column submission.netting_declaration.declared_at is 'class: operational';
