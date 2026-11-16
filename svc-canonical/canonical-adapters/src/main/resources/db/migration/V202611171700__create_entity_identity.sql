-- V202611171700__create_entity_identity.sql (INV-T-21533; INV-REQ-479, INV-Q-21451 A: the entity id of EntityProfileChanged).
CREATE TABLE canonical.entity_identity (
    tenant_id uuid NOT NULL,
    entity_ref text NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    entity_id uuid NOT NULL,
    version bigint NOT NULL CHECK (version >= 1),
    PRIMARY KEY (tenant_id, entity_ref)
);
ALTER TABLE canonical.entity_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.entity_identity FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON canonical.entity_identity
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
GRANT SELECT, INSERT, UPDATE ON canonical.entity_identity TO canonical_runtime;
