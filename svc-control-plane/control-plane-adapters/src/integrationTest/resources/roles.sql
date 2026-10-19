-- roles.sql (CS-19, S2-05): the role family a service's provisioning gives it (INV-REQ-1189 b2). It creates roles and
-- grants to them only (gst.jooq-codegen initScripts, INV-Q-4457); the integration tests run it first, then
-- schema.sql, as Testcontainers' superuser init scripts.
--   controlplane_migrate  the migration login: owns the schema and every table
--   controlplane_sys      NOLOGIN, owns the definer functions, granted to controlplane_migrate only
--   controlplane_runtime  the one runtime login: owns nothing, a member of no role
CREATE ROLE controlplane_migrate LOGIN PASSWORD 'controlplane_migrate_pw' NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
CREATE ROLE controlplane_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT controlplane_sys TO controlplane_migrate;
CREATE ROLE controlplane_runtime LOGIN PASSWORD 'controlplane_runtime_pw' NOSUPERUSER NOBYPASSRLS;
