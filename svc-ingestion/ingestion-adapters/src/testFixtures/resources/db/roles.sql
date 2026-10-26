-- Roles of INV-AC-2175, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE ingestion_migrate LOGIN PASSWORD 'ingestion_migrate_pw';
CREATE ROLE ingestion_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'ingestion_runtime_pw';
CREATE ROLE ingestion_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT ingestion_sys TO ingestion_migrate;
