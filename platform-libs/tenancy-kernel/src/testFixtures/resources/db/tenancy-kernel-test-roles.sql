-- tenancy-kernel-test-roles.sql (CS-19, ARC-20, S2-05, V-07)
--
-- Run once by Testcontainers' default superuser as the container's init
-- script. Creates the two roles every service's own migration set follows the
-- same shape for: an owner role that runs migrations and owns the schema, and
-- an application role the tests actually connect as — LOGIN, NOSUPERUSER,
-- NOBYPASSRLS, owning no table, so every RLS assertion is real rather than
-- vacuously true (the mistake this file exists to rule out, S2-05).

CREATE ROLE tenancy_kernel_owner LOGIN PASSWORD 'tenancy_kernel_owner_pw';
CREATE ROLE tenancy_kernel_app   LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'tenancy_kernel_app_pw';

CREATE SCHEMA tenancy_kernel_test AUTHORIZATION tenancy_kernel_owner;

GRANT USAGE ON SCHEMA tenancy_kernel_test TO tenancy_kernel_app;
ALTER DEFAULT PRIVILEGES FOR ROLE tenancy_kernel_owner IN SCHEMA tenancy_kernel_test
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tenancy_kernel_app;
