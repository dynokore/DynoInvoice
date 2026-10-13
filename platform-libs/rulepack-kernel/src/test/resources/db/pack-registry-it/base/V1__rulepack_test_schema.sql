-- PackRegistryMigrationPostgresIT (INV-T-4188, INV-T-4137): the schema the pack registry's generated DDL is applied in,
-- and the roles that DDL names. rulepack_kernel_sys is the NOLOGIN owner of register_platform_pack (INV-ADR-3968
-- item 6); rulepack_kernel_app is the runtime login; rulepack_kernel_other is a login the function is not granted to.
CREATE SCHEMA rulepack_test;
CREATE ROLE rulepack_kernel_sys NOLOGIN NOSUPERUSER NOBYPASSRLS;
CREATE ROLE rulepack_kernel_app LOGIN PASSWORD 'rulepack_kernel_app_pw' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE rulepack_kernel_other LOGIN PASSWORD 'rulepack_kernel_other_pw' NOSUPERUSER NOBYPASSRLS;
