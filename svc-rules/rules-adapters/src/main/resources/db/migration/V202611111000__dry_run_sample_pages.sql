-- INV-T-19903 (INV-T-4974 slice 3; INV-REQ-772, INV-REQ-1303, INV-Q-4870 B, INV-Q-4995): the pages of the historical
-- sample of one dry run, stored as the PackDryRunSampleRecorded events delivered them. rules-svc keeps no standing store
-- of tenant transaction facts (INV-REQ-1196): the rows live until the run ends and `release` deletes them.
-- Relational columns only: no json, jsonb or bytea. `period_ref` and `doc_uuid` are the members of the page item as
-- received, so a missing or malformed value stays visible to the run (nullable text, no check).

create table rules.dry_run_sample_page (
    tenant_id   uuid        not null,
    dry_run_id  text        not null check (dry_run_id !~ '^\s*$' and char_length(dry_run_id) <= 40),
    page_number integer     not null check (page_number >= 1),
    last_page   boolean     not null,
    received_at timestamptz not null,
    primary key (tenant_id, dry_run_id, page_number)
);

create table rules.dry_run_sample_item (
    tenant_id               uuid    not null,
    dry_run_id              text    not null,
    page_number             integer not null,
    position                integer not null check (position >= 1),
    entity_ref              text    not null,
    transaction_date        date    not null,
    internal_tax_code       text    not null,
    document_function       text    not null,
    supply_type             text    null,
    preceding_document_date date    null,
    taxable_value           numeric not null,
    gst_value               numeric not null,
    sgd_values_absent       boolean null,
    period_ref              text    null,
    doc_uuid                text    null,
    primary key (tenant_id, dry_run_id, page_number, position),
    foreign key (tenant_id, dry_run_id, page_number)
        references rules.dry_run_sample_page (tenant_id, dry_run_id, page_number)
);

alter table rules.dry_run_sample_page enable row level security;
alter table rules.dry_run_sample_page force row level security;
alter table rules.dry_run_sample_item enable row level security;
alter table rules.dry_run_sample_item force row level security;

create policy dry_run_sample_page_tenant_read on rules.dry_run_sample_page for select
    using (tenant_id = (select rules.bound_tenant()));
create policy dry_run_sample_page_tenant_insert on rules.dry_run_sample_page for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy dry_run_sample_page_tenant_delete on rules.dry_run_sample_page for delete
    using (tenant_id = (select rules.bound_tenant()));
create policy dry_run_sample_item_tenant_read on rules.dry_run_sample_item for select
    using (tenant_id = (select rules.bound_tenant()));
create policy dry_run_sample_item_tenant_insert on rules.dry_run_sample_item for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy dry_run_sample_item_tenant_delete on rules.dry_run_sample_item for delete
    using (tenant_id = (select rules.bound_tenant()));

-- Written once per page, read by the run, deleted when the run ends: no update, nothing for PUBLIC.
revoke all on rules.dry_run_sample_page, rules.dry_run_sample_item from public;
grant select, insert, delete on rules.dry_run_sample_page to rules_runtime;
grant select, insert, delete on rules.dry_run_sample_item to rules_runtime;
