-- V202611151000__document_hold.sql (INV-T-20654; INV-REQ-479, ruling of the coordinator 2026-11-12: option A).
-- Expand step: three nullable columns on canonical.document. hold_reasons is written by the hold use case (the state
-- HELD itself is already allowed by the state check); the other two are facts a later slice writes.
alter table canonical.document add column posting_timestamp timestamptz;
alter table canonical.document add column invoice_issued_or_received boolean;
alter table canonical.document add column hold_reasons text[];
-- Least privilege (the pattern of V3__document.sql): the runtime login updates hold_reasons only.
grant update (hold_reasons) on canonical.document to canonical_runtime;
