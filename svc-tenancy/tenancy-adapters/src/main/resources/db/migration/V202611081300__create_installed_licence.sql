-- V202611081300: the signed licences installed on the installation (INV-REQ-1076, INV-REQ-907; INV-AC-9590). Installation
-- scoped, not tenant data: no tenant column, like installation_fact. A licence id is stored once and never replaced.
SET lock_timeout = '5s';

CREATE TABLE tenancy.installed_licence (
    seq          bigint GENERATED ALWAYS AS IDENTITY,
    licence_id   text        NOT NULL PRIMARY KEY CHECK (length(licence_id) BETWEEN 1 AND 64),
    status       text        NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED')),
    expires_at   date        NOT NULL,
    installed_at timestamptz NOT NULL
);
COMMENT ON TABLE tenancy.installed_licence IS 'platform-scoped: no tenant data';
GRANT SELECT, INSERT ON tenancy.installed_licence TO tenancy_runtime;
