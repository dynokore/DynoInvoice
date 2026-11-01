SET lock_timeout = '5s';

-- INV-T-7277, INV-REQ-540 (behavior 3), INV-AC-1760: the response of a transmission attempt and the instant it arrived,
-- recorded beside the request and requested_at of INV-T-7275. Both stay null until the outcome is applied.
alter table submission.submission_attempt add column response bytea;
alter table submission.submission_attempt add column responded_at timestamptz;
comment on column submission.submission_attempt.response is 'class: content';

-- INV-Q-14313 A, INV-Q-14319 A: the failure series of the retry planner and the start of the acknowledgement timer.
alter table submission.submission add column retry_failures integer not null default 0 check (retry_failures >= 0);
alter table submission.submission add column retry_first_failure_at timestamptz;
alter table submission.submission add constraint submission_retry_series_check
    check ((retry_failures = 0) = (retry_first_failure_at is null));
alter table submission.submission add column ack_timer_started_at timestamptz;

-- INV-Q-14325 A: the tenants with a due RETRY row, for the due runner, which binds no tenant yet. Modelled on
-- claim_due: SECURITY DEFINER, owned by submission_sys, search_path pinned, EXECUTE for submission_runtime only. It
-- returns tenant ids and nothing else; submission_sys sees only the three columns it needs.
create policy submission_sys_select on submission.submission for select to submission_sys using (true);
grant select (tenant_id, state, next_attempt_at) on submission.submission to submission_sys;
create function submission.retry_due_tenants(p_now timestamptz)
returns table (due_tenant uuid)
language sql stable security definer set search_path = pg_catalog, submission, pg_temp as $$
    select distinct s.tenant_id from submission.submission s
    where s.state = 'RETRY' and s.next_attempt_at is not null and s.next_attempt_at <= p_now
$$;
grant create on schema submission to submission_sys;
alter function submission.retry_due_tenants(timestamptz) owner to submission_sys;
revoke create on schema submission from submission_sys;
revoke all on function submission.retry_due_tenants(timestamptz) from public;
grant execute on function submission.retry_due_tenants(timestamptz) to submission_runtime;
