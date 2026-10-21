-- Roles of INV-AC-2175, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE payload_migrate LOGIN PASSWORD 'payload_migrate_pw';
CREATE ROLE payload_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'payload_runtime_pw';
CREATE ROLE payload_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT payload_sys TO payload_migrate;
