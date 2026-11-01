SET lock_timeout = '5s';

-- INV-T-7319, INV-Q-15333 A: the tenants that hold a READY version with no version of the same Doc UUID in flight, for
-- WaitingSweeper. Modelled on retry_due_tenants: it returns tenant ids and nothing else, and submission_sys gains the
-- one column it still needs (doc_uuid) beside the three of attempt_outcome. The four states are the ones of
-- InFlight.NAMES and of submission_in_flight_uq, whose predicate the not exists probe uses.
grant select (doc_uuid) on submission.submission to submission_sys;
create function submission.waiting_tenants()
returns table (waiting_tenant uuid)
language sql stable security definer set search_path = pg_catalog, submission, pg_temp as $$
    select distinct s.tenant_id from submission.submission s
    where s.state = 'READY'
      and not exists (
          select 1 from submission.submission o
          where o.tenant_id = s.tenant_id and o.doc_uuid = s.doc_uuid
            and o.state in ('QUEUED', 'SENT', 'SENT_UNCONFIRMED', 'RETRY'))
$$;
grant create on schema submission to submission_sys;
alter function submission.waiting_tenants() owner to submission_sys;
revoke create on schema submission from submission_sys;
revoke all on function submission.waiting_tenants() from public;
grant execute on function submission.waiting_tenants() to submission_runtime;
