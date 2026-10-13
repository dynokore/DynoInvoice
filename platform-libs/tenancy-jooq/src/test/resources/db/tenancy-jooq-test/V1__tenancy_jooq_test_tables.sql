-- tenancy-jooq's isolation-test tables (INV-T-4133). Row-level security is deliberately NOT enabled on them, so the
-- tests prove the jOOQ layer alone: any row of another tenant a statement returns or changes came through tenancy-jooq.
-- Run by Flyway as tenancy_kernel_owner; the default privileges grant the runtime login SELECT, INSERT, UPDATE and
-- DELETE (tenancy-kernel's test roles).

CREATE TABLE tenancy_kernel_test.customers (
    id        uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    name      text NOT NULL
);

CREATE TABLE tenancy_kernel_test.orders (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL,
    customer_id uuid,
    amount      integer NOT NULL
);

CREATE TABLE tenancy_kernel_test.currencies (
    code      text NOT NULL,
    tenant_id uuid NOT NULL,
    name      text NOT NULL,
    PRIMARY KEY (tenant_id, code)
);

-- platform-scoped: no tenant data
CREATE TABLE tenancy_kernel_test.countries (
    code text PRIMARY KEY,
    name text NOT NULL
);
