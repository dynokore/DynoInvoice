SET lock_timeout = '5s';

-- V202611061100__create_signatory_action.sql (INV-T-17453; CS-19, ARC 4.3)
-- Run only by the service's migration Job as access_migrate (never at start-up).
-- The evidence of a signatory action on a signed link (INV-REQ-900): who acted is always `link:<linkRef>`, never a
-- role. The client entity comes from the link record. A repeat of (link, kind, pack version) is the same record.
CREATE TABLE access.signatory_action (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    link_ref     text        NOT NULL CHECK (length(link_ref) BETWEEN 1 AND 64),
    kind         text        NOT NULL CHECK (kind IN ('ACKNOWLEDGE', 'QUERY', 'WITHDRAW')),
    pack_version text        NOT NULL CHECK (length(pack_version) <= 64),  -- empty for WITHDRAW
    query_text   text        CHECK (query_text IS NULL OR length(query_text) BETWEEN 1 AND 2000),
    client_ref   text        NOT NULL CHECK (length(client_ref) BETWEEN 1 AND 64),
    actor        text        NOT NULL CHECK (actor = 'link:' || link_ref),
    recorded_at  timestamptz NOT NULL,
    created_at   timestamptz NOT NULL,
    created_by   text        NOT NULL CHECK (length(created_by) BETWEEN 1 AND 255),  -- the acting user ID, never a role
    UNIQUE (tenant_id, link_ref, kind, pack_version),
    FOREIGN KEY (tenant_id, link_ref) REFERENCES access.signed_link (tenant_id, link_ref)
);

ALTER TABLE access.signatory_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.signatory_action FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.signatory_action
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

-- append-only evidence: no update, no delete
GRANT SELECT, INSERT ON access.signatory_action TO access_runtime;
