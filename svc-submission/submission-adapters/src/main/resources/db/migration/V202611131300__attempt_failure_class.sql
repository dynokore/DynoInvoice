SET lock_timeout = '5s';

-- INV-T-20587, INV-REQ-575 (coordinator ruling, owner 2026-10-14, option A): the failure class of a dead-lettered
-- version is the failure class of its last attempt. Expand-only: a nullable column, no default, nothing rewritten, so
-- every attempt row written before this migration keeps null and a replay scope never selects it by class.
alter table submission.submission_attempt
    add column failure_class text check (failure_class ~ '^[A-Z]{3}-[0-9]{3}$');
comment on column submission.submission_attempt.failure_class is 'class: operational';
