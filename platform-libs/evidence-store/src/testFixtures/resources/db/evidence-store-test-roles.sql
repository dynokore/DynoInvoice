-- evidence-store-test-roles.sql (CS-19, CS-22, ARC-20, S2-05, V-07)
--
-- Run once by Testcontainers' default superuser as the container's init
-- script. Creates the two roles this module's own migration DDL targets: an
-- owner role that runs migrations (and so still has UPDATE/DELETE on its own
-- tables, which is exactly why EvidenceLedgerSchema's trigger — not the grant
-- alone — is the control that also binds it), and an application role the
-- tests actually connect as — LOGIN, NOSUPERUSER, NOBYPASSRLS, owning no
-- table, so every RLS and append-only assertion is real rather than
-- vacuously true (the mistake this file exists to rule out, S2-05).
--
-- Default privileges grant only SELECT and INSERT (CS-22: append-only by
-- grant) — the legal_hold table's own UPDATE/DELETE grants are added
-- explicitly, after it is created, by the fixture that owns that table.

CREATE ROLE evidence_store_owner LOGIN PASSWORD 'evidence_store_owner_pw';
CREATE ROLE evidence_store_app   LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'evidence_store_app_pw';

CREATE SCHEMA evidence_store_test AUTHORIZATION evidence_store_owner;

GRANT USAGE ON SCHEMA evidence_store_test TO evidence_store_app;
ALTER DEFAULT PRIVILEGES FOR ROLE evidence_store_owner IN SCHEMA evidence_store_test
    GRANT SELECT, INSERT ON TABLES TO evidence_store_app;
