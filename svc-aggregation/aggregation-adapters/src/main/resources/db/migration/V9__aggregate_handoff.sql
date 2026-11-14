-- V9__aggregate_handoff.sql (INV-T-4979; INV-REQ-718, INV-REQ-434 item 4, INV-REQ-439): the lines of an aggregate, the
-- hand-off mark, the transmitted mark and the replacement and supplementary marks.
-- transmitted_at is written in production only by the SubmissionDespatched consumer of INV-T-4980 (INV-REQ-439); this
-- service reads it. superseded_by is the id of the aggregate that replaced this one (never deleted). The document
-- number of a replaced aggregate is kept by its replacement (a number is not re-issued, INV-REQ-436 item 3), so
-- the uniqueness of the number holds among aggregates not superseded.
alter table aggregation.aggregate
    add column transmitted_at timestamptz,
    add column superseded_by uuid,
    add column supplementary boolean not null default false,
    add column handed_on_at timestamptz;
alter table aggregation.aggregate drop constraint aggregate_document_number_key;
create unique index aggregate_document_number_key on aggregation.aggregate (tenant_id, document_number)
    where superseded_by is null;
grant update (superseded_by, handed_on_at) on aggregation.aggregate to aggregation_runtime;

create table aggregation.aggregate_line (
    tenant_id uuid not null,
    aggregate_id uuid not null references aggregation.aggregate (aggregate_id),
    position integer not null check (position >= 0),
    internal_tax_code text not null,
    amount text not null check (amount ~ '^-?[0-9]+(\.[0-9]+)?$'),
    primary key (aggregate_id, position)
);
alter table aggregation.aggregate_line enable row level security;
alter table aggregation.aggregate_line force row level security;
create policy aggregate_line_tenant_isolation on aggregation.aggregate_line
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert on aggregation.aggregate_line to aggregation_runtime;
