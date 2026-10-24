-- Roles of INV-AC-2175, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE notification_migrate LOGIN PASSWORD 'notification_migrate_pw';
CREATE ROLE notification_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'notification_runtime_pw';
CREATE ROLE notification_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT notification_sys TO notification_migrate;
