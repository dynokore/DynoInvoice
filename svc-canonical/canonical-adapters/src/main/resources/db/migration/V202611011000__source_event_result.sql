-- V202611011000__source_event_result.sql (INV-T-14191; INV-REQ-789 behavior 3, ruling INV-Q-14185).
-- Keeps the result of the first record with the source_event row so a repeat of the same four-part key returns it.
-- The column holds the fields of RecordDocumentResponse (no party or line text), so it is not content.
-- NOT VALID: rows older than this migration stay readable; every new row must carry a result.
alter table canonical.source_event add column result jsonb;
alter table canonical.source_event add constraint source_event_result_present check (result is not null) not valid;
