-- INV-T-21157: the tenant table of the deferral marker (INV-AC-21156). The contract `Deferral` has no until and no
-- reason member, so none is stored (INV-Q-21112 A). A DEFERRED row is unique per (tenant, entity, target kind, target
-- ref); an UNDONE row is history and is never deleted. Forced row level security; the section between the markers is
-- the unchanged output of TenantRlsMigration.ddl, kept equal by DeferralStoreDbTest.
create table console_bff.deferral (
    tenant_id uuid not null,
    deferral_id uuid not null,
    entity_ref text not null,
    target_kind text not null check (target_kind in ('DOCUMENT', 'GROUP')),
    target_ref text not null,
    state text not null check (state in ('DEFERRED', 'UNDONE')),
    deferred_by text not null,
    deferred_at timestamptz not null,
    primary key (tenant_id, deferral_id)
);
create unique index deferral_one_deferred on console_bff.deferral (tenant_id, entity_ref, target_kind, target_ref)
    where state = 'DEFERRED';
-- BEGIN TenantRlsMigration.ddl("console_bff", "deferral")
ALTER TABLE console_bff.deferral ENABLE ROW LEVEL SECURITY;
ALTER TABLE console_bff.deferral FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON console_bff.deferral
    USING      (tenant_id = (select console_bff.bound_tenant()))
    WITH CHECK (tenant_id = (select console_bff.bound_tenant()));
-- END TenantRlsMigration.ddl
grant select, insert, update on console_bff.deferral to console_bff_runtime;
-- provisioning's default privileges also grant delete on every new table; an undone row is history, never deleted
revoke delete on console_bff.deferral from console_bff_runtime;
