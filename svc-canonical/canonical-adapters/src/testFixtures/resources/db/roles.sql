-- Roles of INV-AC-2175, run as superuser before the migrations (gst.jooq-codegen initScripts, INV-Q-4457; the tests).
CREATE ROLE canonical_migrate LOGIN PASSWORD 'canonical_migrate_pw';
CREATE ROLE canonical_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'canonical_runtime_pw';
CREATE ROLE canonical_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT canonical_sys TO canonical_migrate;
