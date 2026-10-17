# tenancy-jooq

The framework tenant predicate on jOOQ (INV-ADR-3968 item 4a, INV-REQ-854 b2 and b6; owner ruling INV-Q-4113). Every statement a service or a platform-libs module runs through jOOQ carries the bound tenant's predicate, injected by the framework and checked again before it runs; row-level security in the database stays the second layer (tenancy-kernel).

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | Every service with a data-plane schema, and the platform-libs modules that use jOOQ (CS-18) |
| Part used | `TenancyJooq.configure`, `TableClasses`, `TenancyProbe` (tests only) |
| Uses | tenancy-kernel |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

## Artefacts

- `tenancy-jooq-api` (`src/main`): `TableClass` (TENANT, REFERENCE, PLATFORM_SCOPED), `TableClasses` and `TableClasses.verify(schema)`, `TenancyTables`, `TenantPredicateViolation`.
- `tenancy-jooq` (`src/impl`): `TenancyJooq.configure(configuration, tables, tenants, transactionRunner)` and `TenancyJooq.dslContext(tables, tenants, transactionRunner)` for platform-libs modules (tables named with `DSL.name(schema, table)`).
- The `test-fixtures` variant (capability `com.dynokore.platform:tenancy-jooq-test-fixtures`, published in `tenancy-jooq-api`'s module metadata): `TenancyProbe` and its Spring test auto-configuration (coordinator ruling INV-Q-4140; owner ruling INV-Q-4196). platform-build's `verifyNoTenancyProbeOnMainClasspath` keeps it off every main and runtime classpath.

## What `TenancyJooq.configure` installs

- A ConnectionProvider that returns only tenancy-kernel's current transaction connection. No binding, no open transaction, or a binding that differs from the open transaction's is refused.
- A VisitListener that rewrites each SELECT (every UNION, INTERSECT and EXCEPT branch, subquery, derived table and CTE body), UPDATE, DELETE, INSERT and MERGE as it renders:
  - a TENANT table gets `<alias>.tenant_id = ?` (the bound tenant as a bind value); a REFERENCE table read gets `tenant_id IN (?, <platform>)`;
  - on an outer join's nullable side the predicate goes into that join's ON clause; a FULL join also keeps the null-extended rows in WHERE;
  - INSERT, INSERT ... SELECT and the insert branch of MERGE set `tenant_id` to the bound tenant when it is absent and refuse any other value; ON CONFLICT DO UPDATE and MERGE are limited to the bound tenant's rows; an UPDATE that sets `tenant_id` is refused;
  - a REFERENCE platform row is never written; a PLATFORM_SCOPED table is read under any binding and written only under the platform-tenant binding.
- An ExecuteListener that renders the statement again with an independent check and refuses it, before it is prepared, unless every TENANT or REFERENCE table carries its predicate and the checked SQL is the SQL about to run.

Refused with `TenantPredicateViolation`, before any SQL reaches the server: plain-SQL query parts (`DSL.sql`, `field(String)`, `table(String)`, `condition(String)`, `resultQuery(String)`), TRUNCATE, DDL, batches and routine calls, a table that is not in the declared schema, an outer NATURAL, USING or OUTER APPLY join whose nullable side reads a tenant table, INSERT without a column list, and INSERT ... SELECT that names `tenant_id`.

`TenancyJooq.problem` (service-runtime's guard) refuses a configuration whose `TenancyTables` did not come from `TableClasses.verify` (a `TenancyTables.declared` instance, or listeners built from two instances), except the library `TenancyJooq.dslContext`; `configure` and `install` still accept a declared instance (INV-Q-4821 B).
