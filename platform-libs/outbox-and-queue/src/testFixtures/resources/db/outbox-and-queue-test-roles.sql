-- outbox-and-queue-test-roles.sql (CS-19, ARC-20, S2-05, INV-REQ-1205)
--
-- Run once by Testcontainers' default superuser as the init script. Roles:
--   outbox_owner  — runs migrations (DDL only).
--   outbox_app    — a service's runtime role: LOGIN, NOSUPERUSER, NOBYPASSRLS, owns no table.
--   outbox_relay  — INSERT on the producer's own event-log topics, and nothing else.
--   outbox_reader — SELECT on the event-log topics a consumer subscribes to, and nothing else.
-- One schema per fixture service (submission, reconciliation, payload, canonical), and `eventlog`.

CREATE ROLE outbox_owner  LOGIN PASSWORD 'outbox_owner_pw';
CREATE ROLE outbox_app    LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'outbox_app_pw';
CREATE ROLE outbox_relay  LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'outbox_relay_pw';
CREATE ROLE outbox_reader LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'outbox_reader_pw';

CREATE SCHEMA submission AUTHORIZATION outbox_owner;
CREATE SCHEMA reconciliation AUTHORIZATION outbox_owner;
CREATE SCHEMA payload AUTHORIZATION outbox_owner;
CREATE SCHEMA canonical AUTHORIZATION outbox_owner;
CREATE SCHEMA eventlog AUTHORIZATION outbox_owner;

GRANT USAGE ON SCHEMA submission, reconciliation, payload, canonical TO outbox_app;
GRANT USAGE ON SCHEMA eventlog TO outbox_relay, outbox_reader;

-- The cross-tenant worker role of ARC 4.3 step 1a: no BYPASSRLS. Its only reach into tenant data is the
-- outbox claim policy and column grant that OutboxSchema.ddl creates; it also holds the offset and dead-letter tables.
CREATE ROLE outbox_worker LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'outbox_worker_pw';
