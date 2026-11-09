SET lock_timeout = '5s';

-- INV-T-19478, INV-REQ-975, INV-AC-19464: the submission type, the submitted-at and the acknowledged-at instants of a
-- submission version, for the submission list. Expand step: all three are nullable with no default; the writers
-- follow in the next slice. There is no CHECK pairing acknowledged_at with acknowledgement_id.
alter table submission.submission add column submission_type text
    check (submission_type in ('TYPE_1A', 'TYPE_1B', 'TYPE_2', 'TYPE_3'));
alter table submission.submission add column submitted_at timestamptz;
alter table submission.submission add column acknowledged_at timestamptz;
comment on column submission.submission.submission_type is 'class: operational';
comment on column submission.submission.submitted_at is 'class: operational';
comment on column submission.submission.acknowledged_at is 'class: operational';

-- backfill:begin
-- submitted_at from the last responded attempt of a version whose acknowledgement timer started. The migration role
-- binds no tenant, so row level security is lifted around the update and forced again after it. submission_type and
-- acknowledged_at have no stored source and stay null.
alter table submission.submission no force row level security;
alter table submission.submission_attempt no force row level security;
update submission.submission s
   set submitted_at = (select a.responded_at
                         from submission.submission_attempt a
                        where a.doc_uuid = s.doc_uuid and a.version = s.version and a.responded_at is not null
                        order by a.attempt_number desc
                        limit 1)
 where s.ack_timer_started_at is not null and s.submitted_at is null;
alter table submission.submission force row level security;
alter table submission.submission_attempt force row level security;
-- backfill:end
