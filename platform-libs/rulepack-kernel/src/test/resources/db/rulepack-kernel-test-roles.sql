-- rulepack-kernel-test-roles.sql (CS-19, S2-05; INV-T-4188, INV-T-4137): run once by Testcontainers' superuser as the
-- init script, the role family a service's provisioning gives it (INV-REQ-1189 b2, owner rulings INV-Q-3931,
-- INV-Q-3953), as config-resolver's and outbox-and-queue's test roles:
--   rulepack_kernel_migrate — the migration login (`<svc>_migrate`): NOSUPERUSER, NOBYPASSRLS, owns the schemas and
--                             the table, member of rulepack_kernel_sys so it can hand register_platform_pack over.
--   rulepack_kernel_sys     — the NOLOGIN owner of register_platform_pack (`<svc>_sys`, INV-ADR-3968 item 6).
--   rulepack_kernel_app     — the runtime login: owns nothing and is a member of no role.
--   rulepack_kernel_other   — a login the function is not granted to.
-- rulepack_migrations stays an empty schema; rulepack_test holds only the pack registry table.
CREATE ROLE rulepack_kernel_migrate LOGIN PASSWORD 'rulepack_kernel_migrate_pw' NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
CREATE ROLE rulepack_kernel_sys NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE;
GRANT rulepack_kernel_sys TO rulepack_kernel_migrate;
CREATE ROLE rulepack_kernel_app LOGIN PASSWORD 'rulepack_kernel_app_pw' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE rulepack_kernel_other LOGIN PASSWORD 'rulepack_kernel_other_pw' NOSUPERUSER NOBYPASSRLS;
CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;
CREATE SCHEMA rulepack_test AUTHORIZATION rulepack_kernel_migrate;
-- A second schema with its own pack registry and its own binding key (INV-T-5364 look-alike probe).
CREATE SCHEMA rulepack_other AUTHORIZATION rulepack_kernel_migrate;
CREATE SCHEMA rulepack_migrations AUTHORIZATION rulepack_kernel_migrate;
