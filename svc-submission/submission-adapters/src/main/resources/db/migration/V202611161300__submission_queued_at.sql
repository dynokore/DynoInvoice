SET lock_timeout = '5s';

-- INV-T-20582, INV-REQ-1370: the instant a version was first queued (expand-only: nullable, no default, no check).
alter table submission.submission add column queued_at timestamptz;
comment on column submission.submission.queued_at is 'class: operational';

-- backfill:begin
-- queued_at of the rows older than this migration, from recorded instants only: the requested_at of the earliest
-- attempt row of the version, else the earliest enqueued_at of a work_queue row of the same tenant whose payload
-- docUuid and version equal the version's, else it stays NULL (never the migration instant). The migration role
-- binds no tenant, so row level security is lifted around the update and forced again after it.
alter table submission.submission no force row level security;
alter table submission.submission_attempt no force row level security;
alter table submission.work_queue no force row level security;
update submission.submission s
   set queued_at = coalesce(
       (select a.requested_at
          from submission.submission_attempt a
         where a.tenant_id = s.tenant_id and a.doc_uuid = s.doc_uuid and a.version = s.version
         order by a.requested_at, a.attempt_number
         limit 1),
       (select min(q.enqueued_at)
          from submission.work_queue q
         where q.tenant_id = s.tenant_id
           and q.payload ->> 'docUuid' = s.doc_uuid::text
           and q.payload ->> 'version' = s.version::text))
 where s.queued_at is null;
alter table submission.submission force row level security;
alter table submission.submission_attempt force row level security;
alter table submission.work_queue force row level security;
-- backfill:end
