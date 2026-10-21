-- Roles of INV-AC-2175, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE evidence_migrate LOGIN PASSWORD 'evidence_migrate_pw';
CREATE ROLE evidence_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'evidence_runtime_pw';
CREATE ROLE evidence_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT evidence_sys TO evidence_migrate;
