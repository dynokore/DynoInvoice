-- V202610220910__create_service_principal.sql (INV-T-4937; INV-REQ-584, INV-REQ-867)
-- A service principal has exactly one of the six types of INV-REQ-584; the five reserved members of the contract enum
-- are not storable. Revocation is by status: the runtime login never deletes.

CREATE TABLE access.service_principal (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    principal_ref   text        NOT NULL CHECK (length(principal_ref) BETWEEN 1 AND 64),
    principal_type  text        NOT NULL CHECK (principal_type IN
        ('INGEST', 'FILING_WORKFLOW', 'AP_CALLBACK', 'REPORTING', 'MONITORING', 'CERTIFICATION')),
    scope           text        NOT NULL CHECK (length(scope) BETWEEN 1 AND 128),
    calling_system  text        CHECK (length(calling_system) BETWEEN 1 AND 64),
    status          text        NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at      timestamptz NOT NULL,
    created_by      text        NOT NULL,
    updated_at      timestamptz NOT NULL,
    updated_by      text        NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, principal_ref),
    CHECK (principal_type <> 'FILING_WORKFLOW' OR calling_system IS NOT NULL)
);
-- The repeat key of createPrincipal (principalType, scope, callingSystem) holds for principals not yet revoked
CREATE UNIQUE INDEX service_principal_repeat_key ON access.service_principal
    (tenant_id, principal_type, scope, coalesce(calling_system, ''))
    WHERE status = 'ACTIVE';

ALTER TABLE access.service_principal ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.service_principal FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.service_principal
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.service_principal TO access_runtime;
