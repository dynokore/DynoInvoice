-- INV-T-21029: the tenant table of the exception read model (INV-AC-21026). Forced row level security; the section
-- between the markers is the unchanged output of TenantRlsMigration.ddl, kept equal by ExceptionStoreDbTest.
create table console_bff.exception (
    tenant_id uuid not null,
    exception_id uuid not null,
    entity_ref text not null,
    doc_uuid text not null,
    code text not null,
    state text not null check (state in ('OPEN', 'RESOLVED', 'OVERRIDDEN')),
    raised_at timestamptz not null,
    remediation_owner text not null,
    disposition text not null,
    period_ref text not null,
    docsequence bigint not null,
    primary key (tenant_id, exception_id)
);
create index exception_tenant_doc on console_bff.exception (tenant_id, doc_uuid);
-- BEGIN TenantRlsMigration.ddl("console_bff", "exception")
ALTER TABLE console_bff.exception ENABLE ROW LEVEL SECURITY;
ALTER TABLE console_bff.exception FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON console_bff.exception
    USING      (tenant_id = (select console_bff.bound_tenant()))
    WITH CHECK (tenant_id = (select console_bff.bound_tenant()));
-- END TenantRlsMigration.ddl
grant select, insert, update on console_bff.exception to console_bff_runtime;
-- provisioning's default privileges also grant delete on every new table; this table is never deleted from
revoke delete on console_bff.exception from console_bff_runtime;
