-- V202611101000__mapping_import_record.sql (INV-T-4973; INV-REQ-833, INV-REQ-831). Expand only.
-- The import record of a mapping spreadsheet re-import and the link of a draft entry to it. Run as rules_migrate.
-- The actor is the user ID of the verified token, never a role (INV-Q-10647). Relational only: the rejected rows are an
-- integer array (row numbers from 1) and one code, no document-shaped column.

create table rules.mapping_import_record (
    tenant_id       uuid        not null,
    import_id       text        not null check (import_id !~ '^\s*$' and char_length(import_id) <= 40),
    entity_ref      text        not null check (entity_ref !~ '^\s*$|[[:cntrl:]]' and char_length(entity_ref) <= 64),
    imported_at     timestamptz not null default clock_timestamp(),
    actor           text        not null check (actor !~ '^\s*$|[[:cntrl:]]'),
    file_hash       text        not null check (file_hash !~ '^\s*$|[[:cntrl:]]' and char_length(file_hash) <= 128),
    file_name       text        not null check (file_name !~ '^\s*$|[[:cntrl:]]' and char_length(file_name) <= 200),
    row_count       integer     not null check (row_count >= 0),
    dry_run_result  text        not null check (dry_run_result in ('CLEAN', 'REJECTED')),
    applied_count   integer     not null check (applied_count >= 0),
    rejected_rows   integer[]   not null default '{}' check (array_position(rejected_rows, null) is null and 0 < all (rejected_rows)),
    rejected_code   text        null check (rejected_code is null or rejected_code = 'RUL-006'),
    pack_id         text        null check (pack_id is null or pack_id !~ '^\s*$'),
    primary key (tenant_id, import_id),
    -- a clean import applied every row and made a draft; a rejected one applied none, made no draft and lists its rows
    constraint mapping_import_record_outcome check (
        (dry_run_result = 'CLEAN' and applied_count = row_count and cardinality(rejected_rows) = 0
            and rejected_code is null and pack_id is not null)
        or (dry_run_result = 'REJECTED' and applied_count = 0 and cardinality(rejected_rows) > 0
            and rejected_code is not null and pack_id is null))
);
create index mapping_import_record_hash_idx on rules.mapping_import_record (tenant_id, entity_ref, file_hash, imported_at desc);

-- Each entry created by an import keeps a reference to its import (INV-REQ-833 b2); an entry authored by hand has none.
alter table rules.pack_entry_source add column import_ref text null;
alter table rules.pack_entry_source add constraint pack_entry_source_import_ref
    foreign key (tenant_id, import_ref) references rules.mapping_import_record (tenant_id, import_id);

alter table rules.mapping_import_record enable row level security;
alter table rules.mapping_import_record force row level security;
create policy mapping_import_record_tenant_read on rules.mapping_import_record for select
    using (tenant_id = (select rules.bound_tenant()));
create policy mapping_import_record_tenant_insert on rules.mapping_import_record for insert
    with check (tenant_id = (select rules.bound_tenant()));

-- Least privilege as V4 does: insert and select only, nothing for PUBLIC; the record is immutable evidence.
revoke all on rules.mapping_import_record from public;
grant select, insert on rules.mapping_import_record to rules_runtime;
