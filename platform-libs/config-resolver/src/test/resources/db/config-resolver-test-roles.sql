-- config-resolver-test-roles.sql (CS-19, S2-05): the roles a service's migration set gives this module.
-- Every runtime role is LOGIN, NOSUPERUSER, NOBYPASSRLS and owns no table, so each RLS assertion is real.
CREATE ROLE config_owner    LOGIN PASSWORD 'config_owner_pw';
CREATE ROLE config_app      LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'config_app_pw';
CREATE ROLE config_platform LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'config_platform_pw';
CREATE ROLE config_purge    LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'config_purge_pw';
CREATE SCHEMA svc AUTHORIZATION config_owner;
