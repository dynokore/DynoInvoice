SET lock_timeout = '5s';

-- INV-T-16762, INV-Q-16754 A, INV-Q-16751 A, INV-REQ-1069: the tenants of the daily compliance evaluation and the
-- emission ledger of its alerts. Modelled on waiting_tenants: the function returns tenant ids and nothing else, and
-- submission_sys gains the two columns it needs (tenant_id, in_scope) of the obligation register entry.
grant select (tenant_id, in_scope) on submission.obligation_register_entry to submission_sys;
create policy obligation_register_entry_sys_select on submission.obligation_register_entry
    for select to submission_sys using (true);
create function submission.compliance_tenants()
returns table (compliance_tenant uuid)
language sql stable security definer set search_path = pg_catalog, submission, pg_temp as $$
    select distinct e.tenant_id from submission.obligation_register_entry e where e.in_scope
$$;
grant create on schema submission to submission_sys;
alter function submission.compliance_tenants() owner to submission_sys;
revoke create on schema submission from submission_sys;
revoke all on function submission.compliance_tenants() from public;
grant execute on function submission.compliance_tenants() to submission_runtime;

-- One row per tenant, entity and period holding the latest day on which the evaluation claimed an alert emission; the
-- claim is written in the same tenant transaction as the outbox write.
create table submission.compliance_alert_ledger (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    measured_on date not null,
    primary key (tenant_id, entity_ref, period_ref)
);
comment on column submission.compliance_alert_ledger.entity_ref is 'class: operational';
comment on column submission.compliance_alert_ledger.period_ref is 'class: operational';
comment on column submission.compliance_alert_ledger.measured_on is 'class: operational';

alter table submission.compliance_alert_ledger enable row level security;
alter table submission.compliance_alert_ledger force row level security;
create policy compliance_alert_ledger_tenant_isolation on submission.compliance_alert_ledger
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.compliance_alert_ledger to submission_runtime;
