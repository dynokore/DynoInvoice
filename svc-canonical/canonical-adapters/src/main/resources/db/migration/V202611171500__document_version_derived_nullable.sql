-- Expand-only: null means not yet derived. No default, no backfill; the check constraints stay and pass a null.
alter table canonical.document_version
    alter column scope_verdict drop not null,
    alter column scope_pack_version drop not null,
    alter column mapping_pack_version drop not null,
    alter column specification_version drop not null;
