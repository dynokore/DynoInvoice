SET lock_timeout = '5s';

-- INV-T-12848, INV-REQ-1069, INV-Q-12800 (A): the obligation register entry, one row per document. The reader joins it
-- to submission.submission on doc_uuid; the due date is a snapshot held here, so a document with no version row has
-- one. The writer of the entries is not part of this slice.
create table submission.obligation_register_entry (
    tenant_id uuid not null,
    doc_uuid text not null primary key,
    entity_ref text not null,
    period_ref text not null,
    due_date date not null,
    in_scope boolean not null,
    kind text not null check (kind in ('INVOICE', 'CREDIT_NOTE'))
);
create index obligation_register_entry_entity_period on submission.obligation_register_entry (entity_ref, period_ref);

alter table submission.obligation_register_entry enable row level security;
alter table submission.obligation_register_entry force row level security;
create policy obligation_register_entry_tenant_isolation on submission.obligation_register_entry
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.obligation_register_entry to submission_runtime;
