-- Roles of INV-AC-2175 for the console service, run as superuser before the migrations (gst.jooq-codegen initScripts; the tests).
CREATE ROLE console_migrate LOGIN PASSWORD 'console_migrate_pw';
CREATE ROLE console_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'console_runtime_pw';
CREATE ROLE console_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT console_sys TO console_migrate;
