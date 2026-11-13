-- V202611131000__create_authority_head.sql (INV-T-9060; INV-Q-17072 A, INV-Q-17070 B; CS-19)
-- Run only by the service's migration Job as access_migrate (never at start-up).
-- The head record of the authority versions: the one row holds the highest authorityVersion among the AuthorityChanged
-- events access-svc has committed. The AuthorityHeadObserved heartbeat reads it; the outbox is transport and is purged,
-- so it is no source (transactional outbox pattern). The table has no tenant column and no row level security: it is
-- platform data, read and written in transactions of every binding. No DELETE is granted (NIST SP 800-53 AC-6).
SET lock_timeout = '5s';

CREATE TABLE access.authority_head (
    singleton         boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    authority_version bigint  NOT NULL CHECK (authority_version >= 1)
);
COMMENT ON TABLE access.authority_head IS 'platform-scoped: no tenant data';

GRANT SELECT, INSERT, UPDATE ON access.authority_head TO access_runtime;
