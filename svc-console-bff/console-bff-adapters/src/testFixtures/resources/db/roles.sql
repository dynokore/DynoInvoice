-- Roles of console-bff, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE console_migrate LOGIN PASSWORD 'console_migrate_pw';
CREATE ROLE console_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'console_runtime_pw';
