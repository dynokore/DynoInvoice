SET lock_timeout = '5s';

-- INV-T-7277, INV-REQ-540 (behavior 3), INV-AC-1760: the response of a transmission attempt and the instant it arrived,
-- recorded beside the request and requested_at of INV-T-7275. Both stay null until the outcome is applied.
alter table submission.submission_attempt add column response bytea;
alter table submission.submission_attempt add column responded_at timestamptz;
comment on column submission.submission_attempt.response is 'class: content';
