SET lock_timeout = '5s';

-- INV-T-22203, INV-REQ-335, INV-AC-22202: the obligation start date of an entity per tenant (event-carried state
-- transfer from EntityProfileChanged). One row per (tenant, entity); the row follows the highest effective date.
create table submission.obligation_start_date (
    tenant_id uuid not null,
    entity_ref text not null check (length(entity_ref) between 1 and 200),
    start_date date not null,
    effective_date date not null,
    primary key (tenant_id, entity_ref)
);

alter table submission.obligation_start_date enable row level security;
alter table submission.obligation_start_date force row level security;
create policy obligation_start_date_tenant_isolation on submission.obligation_start_date
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.obligation_start_date to submission_runtime;
