-- Provisioning of the test database (INV-T-14458), run by the postgres image's init as superuser after 00-roles.sql.
-- The schema is created by provisioning, owned by the migration role, as platform-release's provisioner does
-- (`CREATE SCHEMA IF NOT EXISTS <schema> AUTHORIZATION <svc>_migrate`); the migration role holds no CREATE on the database.
create schema console authorization console_migrate;
grant usage on schema console to console_runtime;
alter default privileges for role console_migrate in schema console grant select, insert, update, delete on tables to console_runtime;
