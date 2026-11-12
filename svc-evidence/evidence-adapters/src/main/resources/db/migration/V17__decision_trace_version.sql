-- V17__decision_trace_version.sql (INV-T-20716; INV-REQ-942, ruling of 2026-10-14).
--
-- The document version of a decision-trace record, in a new nullable column of evidence.decision_trace only (expand
-- step of an expand-contract change). Rows stored before this migration keep a null version. The column is outside
-- the content hash. No trigger, function or session setting feeds it: the ledger writes it from the index it is given.
-- The immutability trigger and the grants of V-earlier migrations stay as they are. It alters no existing object
-- other than adding the column.

alter table evidence.decision_trace add column version integer check (version is null or version >= 1);
create index decision_trace_doc_version_idx on evidence.decision_trace (tenant_id, doc_uuid, version);
