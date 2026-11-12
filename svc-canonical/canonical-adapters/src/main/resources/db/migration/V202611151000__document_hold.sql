-- V202611151000__document_hold.sql (INV-T-20654; INV-REQ-479, coordinator ruling 2026-11-12, option A).
-- Expand step: six nullable columns on canonical.document. entity_ref, document_date and prescribed_period_ref are the
-- text facts the DocumentHeld payload carries; the record pipeline slice writes them, with posting_timestamp and
-- invoice_issued_or_received. hold_reasons is written by the hold use case (the state check already allows HELD).
alter table canonical.document add column entity_ref text;
alter table canonical.document add column document_date date;
alter table canonical.document add column prescribed_period_ref text;
alter table canonical.document add column posting_timestamp timestamptz;
alter table canonical.document add column invoice_issued_or_received boolean;
alter table canonical.document add column hold_reasons text[];
-- Least privilege (the pattern of V3__document.sql): the runtime login updates hold_reasons of the new columns only.
grant update (hold_reasons) on canonical.document to canonical_runtime;
