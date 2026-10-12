-- config-resolver-test-roles.sql (CS-19, S2-05): the role family a service's provisioning gives it (INV-REQ-1189 b2,
-- owner rulings INV-Q-3931, INV-Q-3953).
--   config_owner — the service's migration login (`<svc>_migrate`): owns the schema and every table, member of
--                  config_sys so it can hand the SECURITY DEFINER functions over.
--   config_app   — the service's one runtime login (`<svc>_runtime`): LOGIN, NOSUPERUSER, NOBYPASSRLS, owns nothing
--                  and is a member of no role, so each RLS and privilege assertion is real.
--   config_sys   — the NOLOGIN capability role (`<svc>_sys`): owns the functions, holds their narrow table rights.
CREATE ROLE config_owner LOGIN PASSWORD 'config_owner_pw';
CREATE ROLE config_app   LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'config_app_pw';
CREATE ROLE config_sys   NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT config_sys TO config_owner;
CREATE SCHEMA svc AUTHORIZATION config_owner;
