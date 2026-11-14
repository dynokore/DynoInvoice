SET lock_timeout = '5s';

-- INV-T-22753, INV-REQ-975, INV-AC-22748: the acknowledgement deadline of a submission version. Expand step: the
-- column is nullable with no default and no check; the writer and the classification follow in later slices.
alter table submission.submission add column acknowledgement_deadline timestamptz;
comment on column submission.submission.acknowledgement_deadline is 'class: operational';
