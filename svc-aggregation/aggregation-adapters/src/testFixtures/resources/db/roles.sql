-- Roles of INV-AC-2175, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE aggregation_migrate LOGIN PASSWORD 'aggregation_migrate_pw';
CREATE ROLE aggregation_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'aggregation_runtime_pw';
CREATE ROLE aggregation_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT aggregation_sys TO aggregation_migrate;
