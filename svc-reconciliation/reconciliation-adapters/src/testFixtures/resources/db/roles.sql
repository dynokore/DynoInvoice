-- Roles of INV-AC-2175, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE reconciliation_migrate LOGIN CREATEROLE PASSWORD 'reconciliation_migrate_pw';
CREATE ROLE reconciliation_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'reconciliation_runtime_pw';
CREATE ROLE reconciliation_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT reconciliation_sys TO reconciliation_migrate;
