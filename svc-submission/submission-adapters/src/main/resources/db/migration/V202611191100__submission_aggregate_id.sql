SET lock_timeout = '5s';

-- INV-T-22811, INV-REQ-439, INV-AC-22807: the aggregate a PayloadReady version belongs to. Expand step: nullable with
-- no default, no backfill; existing rows stay null.
alter table submission.submission add column aggregate_id text check (aggregate_id <> '');
comment on column submission.submission.aggregate_id is 'class: operational';
