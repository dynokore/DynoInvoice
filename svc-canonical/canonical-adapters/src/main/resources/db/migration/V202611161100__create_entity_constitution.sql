-- V202611161100__create_entity_constitution.sql (INV-T-21400; INV-REQ-479, ruling INV-Q-21383 A).
CREATE TABLE canonical.entity_constitution (
    tenant_id uuid NOT NULL,
    entity_ref text NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 64),
    constitution text NOT NULL CHECK (length(constitution) BETWEEN 1 AND 40),
    version bigint NOT NULL CHECK (version >= 1),
    PRIMARY KEY (tenant_id, entity_ref)
);
ALTER TABLE canonical.entity_constitution ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.entity_constitution FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON canonical.entity_constitution
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
GRANT SELECT, INSERT, UPDATE ON canonical.entity_constitution TO canonical_runtime;
