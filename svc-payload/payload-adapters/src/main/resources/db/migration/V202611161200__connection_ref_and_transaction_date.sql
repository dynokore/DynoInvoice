-- The connection that selects the specification family (VP-71) and the document date it is read at (INV-T-23669).
-- Both are nullable: an event without the member stores null, never a default. No grant change.
alter table payload.submission_type_decision add column connection_ref text;
alter table payload.canonical_input add column transaction_date date;
