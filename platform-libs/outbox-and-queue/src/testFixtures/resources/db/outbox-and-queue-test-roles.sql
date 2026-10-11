-- outbox-and-queue-test-roles.sql (CS-19, ARC-20, S2-05, INV-REQ-1205)
--
-- Run once by Testcontainers' default superuser as the init script. Roles:
--   outbox_owner  — runs migrations (DDL only).
--   outbox_app    — a service's runtime role: LOGIN, NOSUPERUSER, NOBYPASSRLS, owns no table.
--   outbox_relay  — INSERT on the producer's own event-log topics, and nothing else.
--   outbox_reader — SELECT on the event-log topics a consumer subscribes to, under the tenant policy only.
--   outbox_worker — also the consumers' event-log claim role: claim policy and SELECT on the claim columns only.
--   eventlog_purge — the event-log component's purge role (INV-Q-3249): DELETE on the topic tables, SELECT on
--                    log_offset and inserted_at, write on eventlog.purge_watermark; not a service role.
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
GRANT USAGE ON SCHEMA eventlog TO outbox_worker;

-- The event-log component's dedicated purge role (INV-REQ-1205 b3, INV-Q-3249): no SUPERUSER, BYPASSRLS or CREATEROLE.
CREATE ROLE eventlog_purge LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE PASSWORD 'eventlog_purge_pw';
GRANT USAGE ON SCHEMA eventlog TO eventlog_purge;

-- INV-T-3385: each fixture consumer's own event-log roles for its replay topics (INV-Q-3601: SELECT by that consumer
-- alone), so a test can show one consumer's roles cannot read another pair's replay topic.
CREATE ROLE recon_el_claim    LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'recon_el_claim_pw';
CREATE ROLE recon_el_reader   LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'recon_el_reader_pw';
CREATE ROLE sub_el_claim      LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'sub_el_claim_pw';
CREATE ROLE sub_el_reader     LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'sub_el_reader_pw';
CREATE ROLE evidence_el_claim  LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'evidence_el_claim_pw';
CREATE ROLE evidence_el_reader LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'evidence_el_reader_pw';
GRANT USAGE ON SCHEMA eventlog TO recon_el_claim, recon_el_reader, sub_el_claim, sub_el_reader, evidence_el_claim,
    evidence_el_reader;
CREATE SCHEMA evidence AUTHORIZATION outbox_owner;
GRANT USAGE ON SCHEMA evidence TO outbox_app;
