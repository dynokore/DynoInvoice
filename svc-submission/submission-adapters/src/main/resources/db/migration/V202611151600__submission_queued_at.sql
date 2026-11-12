SET lock_timeout = '5s';

-- INV-T-20582, INV-REQ-1370: the instant a version was first queued (expand-only; rows older than this migration keep NULL).
alter table submission.submission add column queued_at timestamptz;
comment on column submission.submission.queued_at is 'class: operational';
