-- V23__document_locator_source_ref.sql (INV-T-24170; INV-REQ-646, INV-AC-24167).
-- The sourceDocumentRef of the canonical version, kept per version beside the locator (reference only, never the
-- content). Nullable: a version without the member stores null. The existing policy, grants and append-only trigger
-- of the table cover the new column.
alter table evidence.document_locator
    add column source_document_ref text
        check (source_document_ref is null or (btrim(source_document_ref) <> '' and char_length(source_document_ref) <= 512));
