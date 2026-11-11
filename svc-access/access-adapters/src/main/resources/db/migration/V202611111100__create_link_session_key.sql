-- V202611111100__create_link_session_key.sql (INV-T-17805; INV-REQ-899, ruling option A of the owner)
-- The public part of the signing key of use LINK_SESSION (NIST SP 800-57: one key, one use). It is platform data, so
-- its rows carry the reserved platform tenant and are read under that binding, like access.verification_key. The
-- private part is never stored: it is a SecretStore secret read at each use. A kid is unique across this table and
-- access.verification_key (the publication refuses a kid shared by two uses).

CREATE TABLE access.link_session_key (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    kid           text        NOT NULL CHECK (length(kid) BETWEEN 1 AND 32),
    alg           text        NOT NULL CHECK (alg = 'RS256'),
    public_key    text        NOT NULL CHECK (length(public_key) BETWEEN 1 AND 8192),
    valid_from    timestamptz NOT NULL,
    valid_until   timestamptz NOT NULL CHECK (valid_until > valid_from),
    UNIQUE (tenant_id, kid)
);

ALTER TABLE access.link_session_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.link_session_key FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.link_session_key
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT ON access.link_session_key TO access_runtime;
