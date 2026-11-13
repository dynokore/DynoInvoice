-- INV-T-21081: the tenant table of period due dates (INV-AC-21077), fed by PeriodDueDateChanged (version >= 1) and
-- by the initial filing due date of a period record (version 0). Forced row level security; the section between the
-- markers is the unchanged output of TenantRlsMigration.ddl, kept equal by PeriodDueDateStoreDbTest.
create table console_bff.period_due_date (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    due_date date not null,
    due_date_version bigint not null check (due_date_version >= 0),
    primary key (tenant_id, entity_ref, period_ref)
);
-- BEGIN TenantRlsMigration.ddl("console_bff", "period_due_date")
ALTER TABLE console_bff.period_due_date ENABLE ROW LEVEL SECURITY;
ALTER TABLE console_bff.period_due_date FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON console_bff.period_due_date
    USING      (tenant_id = (select console_bff.bound_tenant()))
    WITH CHECK (tenant_id = (select console_bff.bound_tenant()));
-- END TenantRlsMigration.ddl
grant select, insert, update on console_bff.period_due_date to console_bff_runtime;
-- provisioning's default privileges also grant delete on every new table; this table is never deleted from
revoke delete on console_bff.period_due_date from console_bff_runtime;
