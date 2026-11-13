-- V202611131000__create_authority_head.sql (INV-T-9060; INV-Q-17072 A, INV-Q-17070 B; CS-19)
-- Run only by the service's migration Job as access_migrate (never at start-up).
-- The head record of the authority versions: the one row holds the highest authorityVersion among the AuthorityChanged
-- events access-svc has committed. The AuthorityHeadObserved heartbeat reads it; the outbox is transport and is purged,
-- so it is no source (transactional outbox pattern). The table has no tenant column and no row level security: it is
-- platform data. No DELETE is granted to any role (NIST SP 800-53 AC-6).
--
-- tenancy-jooq refuses a write of a platform-scoped table outside the platform binding, and the three append sites
-- that advance this record run under tenant bindings (INV-Q-16901 amendment, breakdown tooling/gap9060b-gap.md,
-- option A). The write goes through the SECURITY DEFINER function below, owned by the NOLOGIN access_sys, as
-- access.claim_idp_sync_tenants already does (V202610240900__create_claim_idp_sync_tenants.sql; PostgreSQL
-- "Writing SECURITY DEFINER Functions Safely").
SET lock_timeout = '5s';

CREATE TABLE access.authority_head (
    singleton         boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    authority_version bigint  NOT NULL CHECK (authority_version >= 1)
);
COMMENT ON TABLE access.authority_head IS 'platform-scoped: no tenant data';

GRANT SELECT ON access.authority_head TO access_runtime;
GRANT SELECT, INSERT, UPDATE ON access.authority_head TO access_sys;

CREATE FUNCTION access.advance_authority_head(p_version bigint) RETURNS SETOF bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
BEGIN
    -- input validation at the trust boundary of a SECURITY DEFINER function (OWASP ASVS V5): a version below 1 is
    -- refused here, not only by the caller, and leaves the stored value unchanged
    IF p_version IS NULL OR p_version < 1 THEN
        RAISE EXCEPTION 'authority version must be at least 1' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
    INSERT INTO access.authority_head AS h (singleton, authority_version) VALUES (true, p_version)
    ON CONFLICT (singleton) DO UPDATE
        SET authority_version = GREATEST(h.authority_version, excluded.authority_version)
    RETURNING h.authority_version;
END
$$;

GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.advance_authority_head(bigint) OWNER TO access_sys;
REVOKE CREATE ON SCHEMA access FROM access_sys;
REVOKE ALL ON FUNCTION access.advance_authority_head(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.advance_authority_head(bigint) TO access_runtime;
