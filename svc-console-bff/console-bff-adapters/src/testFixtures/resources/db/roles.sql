-- Roles of console-bff, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE console_bff_migrate LOGIN PASSWORD 'console_bff_migrate_pw';
CREATE ROLE console_bff_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'console_bff_runtime_pw';
CREATE ROLE console_bff_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT console_bff_sys TO console_bff_migrate;
