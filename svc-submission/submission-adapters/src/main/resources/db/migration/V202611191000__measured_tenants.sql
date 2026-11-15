SET lock_timeout = '5s';

-- INV-T-22884, INV-REQ-956, INV-AC-22871: the tenants whose unsubmitted in-scope documents are measured for the
-- event-fed offboarding count. Modelled on compliance_tenants: the function returns tenant ids and nothing else; the
-- register source (any in_scope value) and the entity node source are united. submission_sys gains the one column it
-- needs of entity_node (tenant_id); tenant_id of the register entry is already granted.
grant select (tenant_id) on submission.entity_node to submission_sys;
create policy entity_node_sys_select on submission.entity_node
    for select to submission_sys using (true);
create function submission.measured_tenants()
returns table (measured_tenant uuid)
language sql stable security definer set search_path = pg_catalog, submission, pg_temp as $$
    select e.tenant_id from submission.obligation_register_entry e
    union
    select n.tenant_id from submission.entity_node n
$$;
grant create on schema submission to submission_sys;
alter function submission.measured_tenants() owner to submission_sys;
revoke create on schema submission from submission_sys;
revoke all on function submission.measured_tenants() from public;
grant execute on function submission.measured_tenants() to submission_runtime;
