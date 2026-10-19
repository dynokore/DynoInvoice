-- Roles the migrations grant to, created before them for jOOQ code generation (INV-Q-4457, TPL-11). Provisioning creates the real ones.
CREATE ROLE tenancy_sys NOLOGIN;
CREATE ROLE tenancy_migrate NOLOGIN;
CREATE ROLE tenancy_runtime NOLOGIN;
