SET lock_timeout = '5s';

-- INV-T-16340, INV-REQ-975 (INV-Q-16315 A): the version of the CanonicalVersionCreated event that wrote the entry. The
-- upsert of the entry changes a row only when its stored source_version is null or lower, so a replay and an older
-- version change nothing. Null for entries that exist before the writer.
alter table submission.obligation_register_entry
    add column source_version integer check (source_version >= 1);
