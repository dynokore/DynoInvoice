-- V202611171500__create_consultant_grant_request.sql (INV-T-15800; INV-REQ-908, INV-REQ-2465; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up). The runtime login owns nothing and
-- never deletes: a request is decided by status.

-- One row per implementation.consultant grant request. requested_by and decided_by are user IDs, never roles.
CREATE TABLE access.consultant_grant_request (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    request_ref    text        NOT NULL CHECK (length(request_ref) BETWEEN 1 AND 64),
    identity_ref   text        NOT NULL CHECK (length(identity_ref) BETWEEN 1 AND 64),
    go_live_date   date        NOT NULL,
    requested_by   text        NOT NULL CHECK (length(requested_by) BETWEEN 1 AND 64),
    status         text        NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'DENIED')),
    decided_by     text        CHECK (decided_by IS NULL OR length(decided_by) BETWEEN 1 AND 64),
    grant_ref      text        CHECK (grant_ref IS NULL OR length(grant_ref) BETWEEN 1 AND 64),
    valid_to       date,
    created_at     timestamptz NOT NULL,
    created_by     text        NOT NULL,
    updated_at     timestamptz NOT NULL,
    updated_by     text        NOT NULL,
    version        bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, request_ref),
    CHECK ((status = 'PENDING') = (decided_by IS NULL)),
    CHECK ((status = 'APPROVED') = (grant_ref IS NOT NULL AND valid_to IS NOT NULL))
);
-- the repeatKey of the contract: one pending request per identity and go-live date in a tenant
CREATE UNIQUE INDEX consultant_grant_request_one_pending
    ON access.consultant_grant_request (tenant_id, identity_ref, go_live_date) WHERE status = 'PENDING';

ALTER TABLE access.consultant_grant_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.consultant_grant_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.consultant_grant_request
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.consultant_grant_request TO access_runtime;
