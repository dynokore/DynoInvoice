# Changelog

All notable changes to `tenancy-kernel` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.0] - 2026-09-26
### Added
- `TenantContext` and `TransactionRunner` ports and implementations (ARC-21):
  tenant context bound per request/message on a `ThreadLocal`, the session
  tenant set with `set_config('app.tenant_id', …, true)` as the first
  statement of every transaction, REQUIRED propagation for a nested
  `inTransaction` call (INV-T-2861 / T17, INV-REQ-854, FR-G.5).
- `TenantAwareDataSource`: clears `app.tenant_id` the moment a connection
  returns to the pool, proven by a Testcontainers test on a pool of size one
  alternating tenants, against both PostgreSQL 17 and 18 (ARC-21's own named
  CI check).
- `TenantRlsMigration`: the mandatory `tenant_id` migration helper — generates
  CS-19's `ENABLE`/`FORCE ROW LEVEL SECURITY` and tenant-isolation policy DDL,
  so a service's own migration never hand-writes it.
- `LicenceCheck` port and `NonBlockingLicenceCheck`: entitlement/licence status
  (SDS §12.1) that structurally never blocks or throws past a bounded timeout,
  regardless of the wrapped implementation (FR-G.12).
- Isolation-test fixtures (`java-test-fixtures`, §9.2/§13.3): a real
  PostgreSQL Testcontainers fixture (`PostgresProbeFixture`) with the owner/app
  role pair (S2-05), the tenant-probe helpers (TPL-14), and `SingleSlotDataSource`
  for the pooled-connection-leak proof — published so a service's own RLS
  tests can depend on `testFixtures(libs.platform.tenancyKernel)`.
- Consumer-driven contract test of the module API: exercises `TenantContext`,
  `TransactionRunner` and `LicenceCheck` the way `submission-application`'s
  use cases are written (TPL-04), against the real implementation.
- `platform-libs-bom` gains this module's `api`/`impl` constraint pair.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
