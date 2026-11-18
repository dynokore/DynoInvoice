SET lock_timeout = '5s';

-- INV-T-24519, INV-REQ-709: the held set of credit notes. Two partial indexes serve the hold store (the held rows of a
-- tenant, and the register entries that name a preceding document), and submission.held_tenants() lists the tenants
-- that hold any version, for the sweep. Modelled on waiting_tenants: tenant ids and nothing else.
-- Grants: the function reads submission.submission (tenant_id, state) only. submission_sys already holds
-- select (tenant_id, state, next_attempt_at, doc_uuid) and the select-all policy submission_sys_select on that table
-- (V202611010900, V202611011100), so this migration grants nothing to submission_sys; submission_runtime gains execute
-- on the one function and public loses it.
create index submission_held_idx on submission.submission (tenant_id, doc_uuid) where state = 'AWAITING_PRECEDING';
create index obligation_register_entry_preceding_idx
    on submission.obligation_register_entry (tenant_id, preceding_doc_uuid) where preceding_doc_uuid is not null;
create function submission.held_tenants()
returns table (held_tenant uuid)
language sql stable security definer set search_path = pg_catalog, submission, pg_temp as $$
    select distinct s.tenant_id from submission.submission s where s.state = 'AWAITING_PRECEDING'
$$;
grant create on schema submission to submission_sys;
alter function submission.held_tenants() owner to submission_sys;
revoke create on schema submission from submission_sys;
revoke all on function submission.held_tenants() from public;
grant execute on function submission.held_tenants() to submission_runtime;
