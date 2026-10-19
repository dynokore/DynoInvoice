-- Roles of INV-AC-2175, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE submission_migrate LOGIN PASSWORD 'submission_migrate_pw';
CREATE ROLE submission_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'submission_runtime_pw';
CREATE ROLE submission_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT submission_sys TO submission_migrate;
