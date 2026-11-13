SET lock_timeout = '5s';

-- INV-T-20692, INV-REQ-570, INV-AC-1778: the provisional register entry of a document held before its scope verdict
-- (ING-016), one row per document. The DocumentHeld consumer places it, DocumentReleased clears reason_code, and the
-- register writer deletes it in the transaction that inserts the derived register entry of the same doc_uuid.
-- Additive: this migration creates one table and alters nothing.
create table submission.provisional_register_entry (
    tenant_id uuid not null,
    doc_uuid text not null,
    entity_ref text not null,
    period_ref text not null,
    direction text not null,
    document_date date not null,
    transaction_date date not null,
    reason_code text,
    docsequence bigint not null check (docsequence >= 1),
    held_at timestamptz not null,
    primary key (tenant_id, doc_uuid)
);

comment on column submission.provisional_register_entry.tenant_id is 'class: operational';
comment on column submission.provisional_register_entry.doc_uuid is 'class: operational';
comment on column submission.provisional_register_entry.entity_ref is 'class: operational';
comment on column submission.provisional_register_entry.period_ref is 'class: operational';
comment on column submission.provisional_register_entry.direction is 'class: operational';
comment on column submission.provisional_register_entry.document_date is 'class: operational';
comment on column submission.provisional_register_entry.transaction_date is 'class: operational';
comment on column submission.provisional_register_entry.reason_code is 'class: operational';
comment on column submission.provisional_register_entry.docsequence is 'class: operational';
comment on column submission.provisional_register_entry.held_at is 'class: operational';

alter table submission.provisional_register_entry enable row level security;
alter table submission.provisional_register_entry force row level security;
create policy provisional_register_entry_tenant_isolation on submission.provisional_register_entry
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update, delete on submission.provisional_register_entry to submission_runtime;
