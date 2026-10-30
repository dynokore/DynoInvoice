SET lock_timeout = '5s';

-- INV-T-7275, INV-REQ-540, INV-REQ-1156, INV-Q-7266 (A), INV-Q-7268 (A): the submission version and its attempts.
-- The two tables of the skeleton (V1) were placeholders keyed by a surrogate id and hold no row of any service
-- (nothing writes them before this slice); they are replaced by the tables keyed (doc_uuid, version). The queue stays
-- the skeleton's work_queue (QueueSchema.ddl): this file adds no queue table. No table is partitioned (INV-Q-7268 A).
drop table submission.submission_attempt;
drop table submission.submission;

-- state holds exactly the 18 names of LifecycleState (INV-T-6317); a test compares the name set by equality.
create table submission.submission (
    tenant_id uuid not null,
    doc_uuid text not null,
    version integer not null check (version >= 1),
    state text not null check (state in ('INGESTED', 'HELD', 'BLOCKED', 'NOT_SUBMITTABLE', 'READY', 'AWAITING_PRECEDING', 'QUEUED', 'SENT', 'SENT_UNCONFIRMED', 'RETRY', 'DEAD_LETTERED', 'REJECTED', 'CANCELLED', 'ACKNOWLEDGED', 'SUPERSEDED', 'NETWORK_ISSUED', 'EXTERNALLY_SUBMITTED', 'WITHDRAWN')),
    payload_bytes bytea,
    payload_sha256 text,
    due_date date,
    attempt_count integer not null default 0,
    next_attempt_at timestamptz,
    acknowledgement_id text,
    -- INV-Q-13469 (A): the entity and the transaction date of the PayloadReady envelope, filled by the consumer slice
    -- (INV-T-7276); the SubmissionDespatched event takes its context from this row. The platform entity and the
    -- tenant-wide entity are refused (EntityRef.kt), as is a blank value (the blank class of the repair migration).
    entity_ref text not null check (entity_ref !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        and entity_ref not in ('ffffffff-ffff-ffff-ffff-ffffffffffff', '00000000-0000-0000-0000-000000000000')),
    transaction_date date,
    primary key (doc_uuid, version)
);
comment on column submission.submission.state is 'class: operational';
comment on column submission.submission.payload_bytes is 'class: content';

create table submission.submission_attempt (
    tenant_id uuid not null,
    doc_uuid text not null,
    version integer not null,
    attempt_number integer not null check (attempt_number >= 1),
    request bytea,
    correlation_id uuid not null,
    requested_at timestamptz not null,
    primary key (doc_uuid, version, attempt_number),
    foreign key (doc_uuid, version) references submission.submission (doc_uuid, version)
);
comment on column submission.submission_attempt.request is 'class: content';

alter table submission.submission enable row level security;
alter table submission.submission force row level security;
create policy submission_tenant_isolation on submission.submission
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
alter table submission.submission_attempt enable row level security;
alter table submission.submission_attempt force row level security;
create policy submission_attempt_tenant_isolation on submission.submission_attempt
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.submission to submission_runtime;
grant select, insert, update on submission.submission_attempt to submission_runtime;
