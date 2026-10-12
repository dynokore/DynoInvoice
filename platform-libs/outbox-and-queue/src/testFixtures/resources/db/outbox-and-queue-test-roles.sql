-- outbox-and-queue-test-roles.sql (CS-19, ARC-20, S2-05, INV-REQ-1205)
--
-- Run once by Testcontainers' default superuser as the init script. Roles:
--   outbox_owner     — runs a service's migrations (DDL only).
--   outbox_app       — a service's one runtime login: LOGIN, NOSUPERUSER, NOBYPASSRLS, owns nothing, member of no role.
--                      It is also the fixture services' event-log login: it produces and subscribes to the catalogue's
--                      topics, and keeps the record topic's position (owner ruling INV-Q-3953, INV-Q-4268).
--   outbox_sys       — a service's NOLOGIN capability role (INV-Q-3953): owns the claim views and SECURITY DEFINER
--                      functions; outbox_owner (the migration role) is its member so it can hand them over.
--   eventlog_sys     — the event log's NOLOGIN definer owner: owns claim, purge_batch and record_position.
--   eventlog_migrate — the event log's migration login, owner of schema eventlog and member of eventlog_sys.
--   eventlog_purge   — the event-log component's purge login (INV-Q-3249): EXECUTE on eventlog.purge_batch() only.
--   outbox_worker    — the role of service-runtime's job and idempotency tables; no event-log right.
--   recon_runtime, sub_runtime, evidence_runtime — each fixture consumer's own runtime login for its replay topics
--                      (INV-Q-3601: read by that consumer alone).
-- One schema per fixture service (submission, reconciliation, payload, canonical, evidence, ...), and `eventlog`.

CREATE ROLE outbox_owner  LOGIN PASSWORD 'outbox_owner_pw';
CREATE ROLE outbox_app    LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'outbox_app_pw';
CREATE ROLE outbox_sys    NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT outbox_sys TO outbox_owner;

CREATE ROLE eventlog_sys     NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
CREATE ROLE eventlog_migrate LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE PASSWORD 'eventlog_migrate_pw';
GRANT eventlog_sys TO eventlog_migrate;
CREATE ROLE eventlog_purge   LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE PASSWORD 'eventlog_purge_pw';

CREATE SCHEMA submission AUTHORIZATION outbox_owner;
CREATE SCHEMA reconciliation AUTHORIZATION outbox_owner;
CREATE SCHEMA payload AUTHORIZATION outbox_owner;
CREATE SCHEMA canonical AUTHORIZATION outbox_owner;
CREATE SCHEMA eventlog AUTHORIZATION eventlog_migrate;

GRANT USAGE ON SCHEMA submission, reconciliation, payload, canonical TO outbox_app;

-- The former cross-tenant worker role: no BYPASSRLS, no event-log right; the role of service-runtime's job and
-- idempotency tables until their (runtime, sys) DDL. OutboxSchema.ddl and QueueSchema.ddl grant it nothing.
CREATE ROLE outbox_worker LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'outbox_worker_pw';

-- INV-T-3385: each fixture consumer's own runtime login for its replay topics (INV-Q-3601: SELECT by that consumer
-- alone), so a test can show one consumer's login cannot read another pair's replay topic.
CREATE ROLE recon_runtime    LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'recon_runtime_pw';
CREATE ROLE sub_runtime      LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'sub_runtime_pw';
CREATE ROLE evidence_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'evidence_runtime_pw';
CREATE SCHEMA evidence AUTHORIZATION outbox_owner;
GRANT USAGE ON SCHEMA evidence TO outbox_app;

-- INV-T-3385 (owner ruling INV-Q-3788 A): the other catalogue producers of gst.platform.evidence-artefact-recorded.v1,
-- so a test can address every producer of a topic with several.
CREATE SCHEMA access AUTHORIZATION outbox_owner;
CREATE SCHEMA masterdata AUTHORIZATION outbox_owner;
CREATE SCHEMA rules AUTHORIZATION outbox_owner;
GRANT USAGE ON SCHEMA access, masterdata, rules TO outbox_app;
