-- V202611021100__create_elevation.sql (INV-T-4941; INV-REQ-862, INV-REQ-888, INV-REQ-890; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up). The runtime login owns nothing and
-- never deletes: a request is decided or withdrawn by status.

-- One row per elevation request (ELV-*, subject = tenant reference) or diagnostic invitation (DIAG-*, subject =
-- installation reference). requester is the operator identity the access opens for; a granted row carries its time box.
CREATE TABLE access.elevation (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    elevation_ref  text        NOT NULL CHECK (length(elevation_ref) BETWEEN 1 AND 64),
    kind           text        NOT NULL CHECK (kind IN ('ELEVATION', 'DIAGNOSTIC_INVITATION')),
    subject_ref    text        NOT NULL CHECK (length(subject_ref) BETWEEN 1 AND 64),
    requester_ref  text        NOT NULL CHECK (length(requester_ref) BETWEEN 1 AND 64),
    case_id        text        NOT NULL CHECK (length(case_id) BETWEEN 1 AND 64),
    status         text        NOT NULL CHECK (status IN ('PENDING', 'GRANTED', 'REFUSED', 'WITHDRAWN')),
    valid_from     timestamptz,
    valid_until    timestamptz,
    decided_by     text        CHECK (decided_by IS NULL OR length(decided_by) BETWEEN 1 AND 64),
    created_at     timestamptz NOT NULL,
    created_by     text        NOT NULL,
    updated_at     timestamptz NOT NULL,
    updated_by     text        NOT NULL,
    version        bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, elevation_ref),
    CHECK ((status = 'GRANTED') = (valid_from IS NOT NULL AND valid_until IS NOT NULL)),
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    CHECK ((kind = 'ELEVATION' AND elevation_ref LIKE 'ELV-%') OR (kind = 'DIAGNOSTIC_INVITATION' AND elevation_ref LIKE 'DIAG-%'))
);
-- the repeatKey of the contract: one pending request per requester, kind, subject and case (withdraw to cancel)
CREATE UNIQUE INDEX elevation_one_pending ON access.elevation (tenant_id, requester_ref, kind, subject_ref, case_id)
    WHERE status = 'PENDING';
CREATE INDEX elevation_by_requester ON access.elevation (tenant_id, requester_ref, kind);

ALTER TABLE access.elevation ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.elevation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.elevation
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.elevation TO access_runtime;
