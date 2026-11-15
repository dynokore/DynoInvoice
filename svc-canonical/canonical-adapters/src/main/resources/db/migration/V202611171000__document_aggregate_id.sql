-- V202611171000__document_aggregate_id.sql (INV-T-22849; INV-REQ-439).
-- Expand step: the aggregate reference of a document, nullable, written once by the aggregate-reference store.
alter table canonical.document add column aggregate_id text check (aggregate_id <> '');
comment on column canonical.document.aggregate_id is
    'INV-REQ-439: the aggregate the document belongs to; set once (never overwritten), absent until bound.';
-- Least privilege (the pattern of V202611151000__document_hold.sql): V3 grants update on four columns only, so the
-- runtime login gets update on this column alone.
grant update (aggregate_id) on canonical.document to canonical_runtime;
