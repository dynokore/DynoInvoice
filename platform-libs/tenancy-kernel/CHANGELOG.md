# Changelog

All notable changes to `tenancy-kernel` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.2.0] - 2026-10-12
### Added
- The tenant binding carries the actor: `TenantContext.bind(tenant, actor, block)`,
  `PlatformTenantBinding.bindPlatform(actor, block)` and `TenantContext.currentActor()`. The actor is
  the end user's `sub`, or the service's workload principal for background work; a blank actor is
  refused (INV-REQ-854 b1; coordinator ruling INV-Q-3997).
- `TransactionRunnerImpl` sets `app.actor` with `set_config('app.actor', :actor, true)` in the same
  first statement as `app.tenant_id`, both transaction-local (ARC-21 as revised; owner rulings
  INV-Q-3951, INV-Q-3953; INV-ADR-3968 item 4; INV-REQ-854 b1, b3, b5). Session-level SET stays
  forbidden. `TenantAwareDataSource` also runs `RESET app.actor` on handback.
### Changed
- A nested `inTransaction` joins the open transaction only when the current binding (tenant and
  actor) equals the open transaction's; otherwise it throws `IllegalStateException` before any
  statement, instead of running silently under the outer tenant (INV-Q-3997).
### Deprecated
- The actor-less `TenantContext.bind(tenant, block)` and `PlatformTenantBinding.bindPlatform(block)`:
  they still work and leave `app.actor` unset. Callers move to the actor-bound forms in INV-T-3979
  (outbox-and-queue), INV-T-3981 (service-runtime) and INV-T-3983 (config-resolver); INV-T-3999
  removes them in a MAJOR release (INV-Q-3997).

## [1.1.0] - 2026-10-11
### Added
- `TenantId.PLATFORM`, the reserved platform tenant (the max UUID
  `ffffffff-ffff-ffff-ffff-ffffffffffff`; Keel INV-Q-2927 option D, INV-Q-3012), and
  `TenantId.isPlatform`. Never provisioned (tenancy-svc refuses it, in its own task).
- `TenantId.fromClaim`: parses a tenant from a user or machine token claim and refuses the
  reserved id, so no token can carry it (INV-Q-3016).
- `PlatformTenantBinding.bindPlatform`, the only way to bind `TenantId.PLATFORM`, implemented by
  `TenantContextImpl`; the composition root hands it only to platform work's adapters (CS-29).
### Changed
- `TenantContext.bind` refuses `TenantId.PLATFORM` with `IllegalArgumentException`.
### Fixed
- `implJar`'s local output no longer collides on disk with the default `jar`
  task's (`tenancy-kernel-<version>.jar`, `build/libs/`): moved to its own
  `build/libs-impl/` directory. No change to what either publishes — the
  Maven `impl` publication's artifact name is unaffected. Found wiring
  evidence-store's cross-module dependency on this module's impl half
  (INV-T-2863 / T19), the first such dependency in this repository; nothing
  had built `:tenancy-kernel:implJar` in the same invocation as a consumer of
  `project(":tenancy-kernel")`'s default output before.

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
