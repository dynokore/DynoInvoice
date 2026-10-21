-- Roles of INV-AC-2175, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE transport_migrate LOGIN PASSWORD 'transport_migrate_pw';
CREATE ROLE transport_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'transport_runtime_pw';
CREATE ROLE transport_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT transport_sys TO transport_migrate;
