-- schema.sql: what provisioning does before the migration Job runs (the schema owned by controlplane_migrate, and
-- pgcrypto in public, STK-14); test-only, run after roles.sql.
CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;
CREATE SCHEMA controlplane AUTHORIZATION controlplane_migrate;
