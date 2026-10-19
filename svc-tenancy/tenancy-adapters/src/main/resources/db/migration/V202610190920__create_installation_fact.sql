-- V202610190920: installation facts (release train and supported window, backup-target verification, installation
-- platform VP-52; coordinator ruling INV-Q-4898). Installation-scoped, not tenant data: INV-T-3290 and the entitlement
-- task write and read it through the repository.
SET lock_timeout = '5s';

CREATE TABLE tenancy.installation_fact (
    fact_key    text        PRIMARY KEY CHECK (length(fact_key) BETWEEN 1 AND 100),
    fact_value  text        NOT NULL CHECK (length(fact_value) BETWEEN 1 AND 1000),
    recorded_at timestamptz NOT NULL,
    version     bigint      NOT NULL DEFAULT 1
);
COMMENT ON TABLE tenancy.installation_fact IS 'platform-scoped: no tenant data';
GRANT SELECT, INSERT, UPDATE ON tenancy.installation_fact TO tenancy_runtime;
