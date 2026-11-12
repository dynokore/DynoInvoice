-- Provisioning of the test database (INV-T-14458), run by the postgres image's init as superuser after 00-roles.sql.
-- The schema is created by provisioning, owned by the migration role, as platform-release's provisioner does
-- (`CREATE SCHEMA IF NOT EXISTS <schema> AUTHORIZATION <svc>_migrate`); the migration role holds no CREATE on the database.
create schema console_bff authorization console_bff_migrate;
grant usage on schema console_bff to console_bff_runtime;
alter default privileges for role console_bff_migrate in schema console_bff grant select, insert, update, delete on tables to console_bff_runtime;
