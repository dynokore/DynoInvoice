-- The aggregate reference of a canonical version, stored once with the version facts (INV-T-22862, INV-AC-22861). Expand-only; the insert-only grant covers the column.
alter table payload.document_version_fact add column aggregate_id text check (aggregate_id <> '');
