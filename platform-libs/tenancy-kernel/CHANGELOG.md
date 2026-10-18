# Changelog

All notable changes to `tenancy-kernel` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [3.3.0] - 2026-10-18
### Added
- Signed binding, slice 2 of 9 of INV-T-4156 (INV-Q-5352, INV-Q-5354 (A), owner ruling INV-Q-4121): `TransactionRunnerImpl`
  takes optional `keys: BindingKeySource` (default none: no signature, the unsigned path is unchanged), `transactionTimeout`
  (default 30 s) and `clock`. With a key source the first statement also sets `app.binding_sig` as a bind parameter,
  expiring no later than the transaction start plus the timeout. `BindingSignature` (impl) signs; testFixtures gain
  `SignedBinding` and `TestPoolFixture.runner(keys, transactionTimeout, clock)`. Nothing reads the signature in a policy yet.

## [3.2.0] - 2026-10-17
### Added
- Signed binding, slice 1 of 9 of INV-T-4156 (INV-Q-5352, owner ruling INV-Q-4121): `BindingKeySource` (main; the
  current key and the previous key) and `BindingKeyMigration` (impl): `ddl` generates the `<svc>_sys`-owned key table
  and the STABLE SECURITY DEFINER `bound_tenant()` / `bound_actor()` that verify `app.binding_sig` (layout in
  README.md "Signed binding") with pgcrypto and return NULL for a missing, forged or expired value; `writeKeys` is the
  migration runner's jOOQ key write. The impl half now depends on jOOQ. Nothing calls the functions yet; the policies,
  `TenantRlsMigration` and the runner are unchanged.

## [3.1.0] - 2026-10-16
### Added
- `TransactionRunner.outsideOpenTransaction(block)` (main; a member with a default body `= block()`, so existing
  implementers compile unchanged; owner answer INV-Q-5609 (b), ruling INV-Q-5683): `TransactionRunnerImpl` clears the
  calling thread's open-transaction marker for the block and restores it in a `finally`, so a run-spanning transaction
  (the outbox relay's advisory lock) and per-row transactions under other tenants coexist on one thread. Nesting
  without the seam is unchanged (INV-Q-3997). A seam user needs at least 2 permits in its lane: the outer and the
  inner connection each take one `LaneGate` permit; `LaneGate` and `TenantAwareDataSource` are unchanged.

## [3.0.0] - 2026-10-15
### Added
- `LaneGate`, `LanePermit` and `LaneRefusedException` (main, no `java.sql` type; owner ruling INV-Q-4502 (A), INV-REQ-1230
  b1): `TenantAwareDataSource(delegate, lanes)` takes a permit before it takes a pool connection and gives it back once
  when the connection is handed back; a refusal is thrown as `SQLTransientConnectionException` (a plain `SQLException`
  when interrupted) with the `LaneRefusedException` as its cause (INV-Q-5121).
- `SqlErrors.sqlStateOf(error)` (main), implemented by `TransactionRunnerImpl`: the first SQLSTATE of the cause chain to
  a depth of 8 (INV-Q-5121). `CurrentConnection.transactionToken()`: an opaque token of the open transaction, so callers
  need no `java.sql` type to tell whether one is open.
- Test fixtures: `TestPoolFixture` (HikariCP from a Testcontainers URL, `TenantAwareDataSource`, handed to
  `TransactionRunnerImpl`; no connection taken, no statement; owner ruling INV-Q-4508 (A)); `SingleSlotDataSource.checkouts`.
### Changed (breaking)
- `ConnectionLabel.of(connection)` is `ConnectionLabel.current()`: the label no longer reads the connection.
- `TenantAwareDataSource` wraps each connection in the private class `TenantAwareDataSource$ResettingConnection`, a
  `Connection` by Kotlin delegation that overrides only `close()`; no `java.lang.reflect.Proxy` (INV-Q-4504).
  `SingleSlotDataSource` is itself a `DataSource` and returns `SingleSlotDataSource$SlotConnection`, also by delegation
  (INV-Q-5153).
### Removed (breaking)
- `ProbeTable` and `PostgresProbeFixture.ownerDataSource`, `appDataSource` and `createProbeTable` (test fixtures,
  INV-Q-5208): the fixture names no `java.sql`, `javax.sql` or `org.postgresql` type; it keeps `jdbcUrl()`, gains
  `OWNER`, `OWNER_PASSWORD`, `APP`, `APP_PASSWORD`, and creates the probe table at container start from
  `TenantRlsMigration.ddl`.
- Test fixtures: `TestPoolFixture.dataSource(lanes)` hands the pool to a Spring context that needs the bean.

## [2.0.0] - 2026-10-15
### Removed (breaking)
- `TenantContext.bind(tenant, block)` and `PlatformTenantBinding.bindPlatform(block)`, the actor-less bindings that
  1.x kept deprecated (coordinator ruling INV-Q-3997, INV-T-3999; INV-REQ-854 b1, INV-ADR-3968 item 4). Every
  binding now carries an actor: use `bind(tenant, actor, block)` and `bindPlatform(actor, block)`.
### Changed
- `TransactionRunner.inTransaction` refuses to open a transaction when the current binding has no actor
  (`IllegalStateException` before a connection is taken or any SQL runs), so every transaction sets both
  `app.tenant_id` and `app.actor`. `TenantContextImpl.currentActor()` never returns null; the nested-binding refusal
  (INV-T-3980) is unchanged.
### Migration
- Replace each `bind(tenant) { ... }` by `bind(tenant, actor) { ... }` (the end user's `sub`, or the service's
  workload principal for background work) and each `bindPlatform { ... }` by `bindPlatform(actor) { ... }`.

## [1.5.0] - 2026-10-14
### Added
- `BlankText` (main, published in `tenancy-kernel-api`): the one definition of a blank value and of a valid actor
  (coordinator rulings INV-Q-4614 (A) and INV-Q-4622 (A), INV-T-4616). Blank: NULL, empty or only U+0001-U+001F, U+007F-U+009F, U+0020, U+00A0, U+1680,
  U+2000-U+200A, U+2028, U+2029, U+202F, U+205F, U+3000 and U+FEFF. Actor: not blank and no ISO control character
  anywhere. `SQL_BLANK` and `SQL_NOT_ACTOR` are the PostgreSQL patterns every module splices into its DDL;
  `isBlank` and `isActor` are the Kotlin checks over the same patterns.
### Changed
- The actor binding (`bind(tenant, actor)`, `bindPlatform(actor)`) refuses an actor by `BlankText.isActor` instead of
  `isNotBlank()`, so an actor the SQL checks would refuse (whitespace only, U+FEFF, or holding a control character)
  is never bound.

## [1.4.1] - 2026-10-14
### Changed
- Metadata-only release (INV-T-4310, coordinator ruling INV-Q-4552): no behaviour or API change.
- The POMs of both publications (api and impl) declare the company licence, `Dynokore Proprietary Licence`
  (SPDX ref `LicenseRef-Dynokore-Proprietary`, no URL yet), stamped by platform-build 0.11.0 (INV-T-4310; owner
  rulings INV-Q-4286 (C) and INV-Q-4284 (A), STK-66, INV-REQ-1383).
- Source formatted to ktlint_official (platform-build 0.11.0, INV-Q-4426, INV-T-4556); formatting only.

## [1.4.0] - 2026-10-13
### Added
- `ConnectionLabel` and an optional third `TransactionRunnerImpl` argument (INV-T-3982; coordinator ruling INV-Q-4487,
  INV-REQ-854 b3): when the label gives a name for the transaction's connection, the binding statement also runs
  `set_config('application_name', ?, true)`, so pg_stat_activity shows it while the transaction runs and it ends at
  commit or rollback like the tenant and actor settings. Without a label, or when it gives `null`, the statement is
  unchanged; the two-argument constructor keeps working.

## [1.3.1] - 2026-10-13
### Fixed
- The published impl POM (`tenancy-kernel`) declared no dependency (INV-T-4356; defect in INV-T-2860's and
  INV-T-3937's per-module publishing, STK-60, STK-01 rule (a), TPL-01): the `impl` publication was built with
  `artifact(implJar)` and no software component, so a consumer declaring platform-libs-bom and this impl artefact had
  to list `tenancy-kernel-api` and the other modules itself. The impl POM now declares `tenancy-kernel-api` at the
  same version and the first-level dependencies of the impl runtime classpath, generated and checked by the root
  build.gradle.kts (`verifyImplPoms`): tenancy-kernel-api:1.3.1 (compile), org.jetbrains.kotlin:kotlin-stdlib:2.4.20
  (compile). The classes are unchanged.

## [1.3.0] - 2026-10-12
### Added
- `SqlIdentifier` (`com.dynokore.platform.tenancykernel.SqlIdentifier`, api): `require(name, what)` returns the
  name only when it matches `^[a-z][a-z0-9_]{0,62}$` and throws `IllegalArgumentException` otherwise. It is the
  one validated identifier type of the platform: an identifier enters SQL text only through it, and
  platform-build 0.9.0's `SqlGuards` accepts no other validator (INV-REQ-854 b5, INV-ADR-3968 item 11;
  coordinator rulings INV-Q-4036, INV-Q-4040; INV-T-4043).
### Changed
- `TenantRlsMigration.ddl` interpolates the schema, table and policy names only through `SqlIdentifier.require`.
  Its own regex validator (`^[a-z_][a-z0-9_]*$`) is removed, so a name with a leading underscore or longer than
  63 characters is now refused instead of being silently truncated by PostgreSQL; the DDL for a valid name is
  unchanged (INV-Q-4036).

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
- The actor-less `TenantContext.bind(tenant, block)` and `PlatformTenantBinding.bindPlatform(block)`,
  marked `@Deprecated` with a `ReplaceWith` naming the actor-bound form: they still work and leave
  `app.actor` unset. Existing call sites in this repository carry a narrow `@Suppress("DEPRECATION")`
  under the line `// INV-T-3999 removes this call` (coordinator ruling, option 1);
  `ActorlessBindingSuppressionsTest` counts them so INV-T-3999 can assert zero. Callers move to the actor-bound forms in INV-T-3979
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
