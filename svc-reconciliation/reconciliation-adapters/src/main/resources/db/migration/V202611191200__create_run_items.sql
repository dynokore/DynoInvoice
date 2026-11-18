-- V202611191200__create_run_items.sql (INV-T-24464, INV-REQ-982, INV-AC-24463).
--
-- The reconciling items of a completed run, one row per item with its evidence references: an audit fact copied at the
-- time of the act and insert-only (NIST SP 800-53 AU-10, AU-9). The trigger refuses update and delete with 42501 and
-- the runtime login holds only select and insert. Tenant isolation is the forced row-level security of every table of
-- the schema; the composite foreign key ties an item to a run of the same tenant. Money keeps scale 2.
create table reconciliation.reconciliation_run_item (
    tenant_id uuid not null,
    run_id uuid not null,
    box text not null check (btrim(box) <> ''),
    doc_uuid text not null,
    version int not null check (version > 0),
    line_number int not null,
    amount numeric(19,2) not null,
    evidence_refs text[] not null check (cardinality(evidence_refs) >= 1),
    primary key (tenant_id, run_id, box, doc_uuid, version, line_number),
    foreign key (tenant_id, run_id) references reconciliation.reconciliation_run (tenant_id, run_id)
);
comment on column reconciliation.reconciliation_run_item.tenant_id is 'class: operational';
comment on column reconciliation.reconciliation_run_item.run_id is 'class: operational';
comment on column reconciliation.reconciliation_run_item.box is 'class: operational';
comment on column reconciliation.reconciliation_run_item.doc_uuid is 'class: operational';
comment on column reconciliation.reconciliation_run_item.version is 'class: operational';
comment on column reconciliation.reconciliation_run_item.line_number is 'class: operational';
comment on column reconciliation.reconciliation_run_item.amount is 'class: operational';
comment on column reconciliation.reconciliation_run_item.evidence_refs is 'class: operational';
ALTER TABLE reconciliation.reconciliation_run_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.reconciliation_run_item FORCE ROW LEVEL SECURITY;
CREATE POLICY reconciliation_run_item_tenant_isolation ON reconciliation.reconciliation_run_item
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create function reconciliation.reconciliation_run_item_append_only() returns trigger
language plpgsql set search_path = pg_catalog, reconciliation, pg_temp as $$
begin
    raise exception 'a reconciliation run row is append-only: it is never updated or deleted' using errcode = '42501';
end
$$;
revoke all on function reconciliation.reconciliation_run_item_append_only() from public;
create trigger reconciliation_run_item_append_only before update or delete on reconciliation.reconciliation_run_item
    for each row execute function reconciliation.reconciliation_run_item_append_only();
revoke update, delete, truncate on reconciliation.reconciliation_run_item from reconciliation_runtime;
grant select, insert on reconciliation.reconciliation_run_item to reconciliation_runtime;
