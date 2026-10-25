# Changelog

All notable changes to `tenancy-jooq` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.4.2] - 2026-10-26
### Changed
- Metadata-only release (INV-Q-10832, INV-Q-10848, INV-Q-10854, INV-T-10836, G-DVS): depends on `tenancy-kernel 4.1.1`, whose published bytes changed; no code or API change.

## [1.4.1] - 2026-10-20
### Changed
- Metadata-only release (INV-T-7520, G-DVS): depends on `tenancy-kernel 4.1.0`, whose published module metadata changed; no code change.

## [1.4.0] - 2026-10-19
### Added
- `TableClasses.verify(List<Schema>)`, an additive overload of the vararg `verify`, for callers that hold the schemas as a list (INV-Q-7401; removes a spread copy in service-runtime)

## [1.3.2] - 2026-10-19
### Changed
- detekt type-resolution findings fixed (platform-build 0.21.0, INV-Q-4629); no behaviour or API change

## [1.3.1] - 2026-10-19
### Changed
- Metadata-only release (INV-Q-5337, G-DVS): depends on `tenancy-kernel` 4.0.0, whose published module metadata changed; no code change.

## [1.3.0] - 2026-10-18
### Added
- `TenancyJooq.configure` and `install` take RecordListeners (default none) and `TenancyJooq.recordListeners` lists a
  configuration's (INV-T-4224: service-runtime's audit-column listener).
### Changed
- The jOOQ settings a consumer may change now include `executeWithOptimisticLocking`,
  `executeWithOptimisticLockingExcludeUnversioned`, `updateRecordVersion` and `updatablePrimaryKeys` (INV-T-4224): they change
  only the version predicate of a record's own UPDATE and DELETE, never a table's identity or the tenant predicate.

## [1.2.3] - 2026-10-18
### Changed
- Signed binding, slice 8 of 9 of INV-T-4156 (INV-T-5365, INV-Q-5352, INV-Q-5354 (A)): `TenancyProbe.inTenant` and `TenancyJooqFixture` sign the tenant binding through tenancy-kernel's `SignedBinding`; test fixtures only, no change to the main or impl code.

## [1.2.2] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5359, G-DVS): depends on `tenancy-kernel` 3.3.0, whose published module metadata changed; no code change.

## [1.2.1] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5358, G-DVS): depends on `tenancy-kernel` 3.2.0, whose published module metadata gained a jOOQ dependency; no code change.

## [1.2.0] - 2026-10-17
### Changed
- `TenancyJooq.problem` (owner answer INV-Q-4821 B, INV-T-5820) also refuses a configuration whose `TenancyTables` did not come from `TableClasses.verify`: the tables held by its predicate injector and by its statement check must be one instance, and that instance must not come from `TenancyTables.declared`; the text names `TableClasses.verify` and `INV-Q-4821`. Each `TenancyTables` carries an internal origin: `VERIFIED` (`TableClasses.verify`), `DECLARED` (`TenancyTables.declared`) or `LIBRARY` (`TenancyJooq.dslContext`, accepted). `declared`, `configure` and `install` still accept a declared instance; the refusal is the guard's, at start-up. The public API is unchanged.

## [1.1.1] - 2026-10-16
### Changed
- Metadata only: depends on `tenancy-kernel` 3.1.0 (adds `TransactionRunner.outsideOpenTransaction`, INV-T-5675); version step per ruling INV-Q-5678 (A). No code change.

## [1.1.0] - 2026-10-15
### Added
- `TenancyJooq.install(configuration, tables, tenants, transactionRunner)`: `configure` applied in place on a
  `DefaultConfiguration` (Spring Boot's customizer), and `TenancyJooq.problem(configuration)`: why a configuration is not
  tenancy-jooq's, or null (coordinator ruling INV-Q-5105). The listener classes stay internal.
### Changed
- Test fixtures: `TenancyJooqFixture` builds its pools from `PostgresProbeFixture`'s URL and login strings (INV-Q-5208).
  `TenancyProbe` logs the number of bind values of a probe, not the values (a probe may bind a password, CS-24).
  Tests: `BlankTextPostgresIT`, `ModuleApiConsumerTransactionContractTest` and `ConfigurationProblemTest` moved here
  from tenancy-kernel and service-runtime.

## [1.0.3] - 2026-10-16
### Changed
- Metadata-only release (INV-T-5340, coordinator ruling INV-Q-5337, delegated, owner 2026-10-14): no API or behaviour
  change; `src/main` and `src/impl` are unchanged since 1.0.2.
- The POMs and module metadata of both publications (api and impl) now name the sibling modules at their current
  versions: tenancy-kernel 2.0.0 (was 1.4.1).

## [1.0.2] - 2026-10-14
### Changed
- org.reactivestreams:reactive-streams pinned to 1.0.4 (MIT-0) by a dependency constraint on the main, impl and
  test-fixtures classpaths, published in tenancy-jooq-api's module metadata, in place of the 1.0.3 (CC0-1.0, not in
  STK-66) that jOOQ 3.21.9's io.r2dbc:r2dbc-spi 1.0.0.RELEASE brings (INV-T-4645; coordinator ruling INV-Q-4643 (A),
  delegated, owner 2026-10-14). No API or behaviour change: 1.0.3 and 1.0.4 hold the same 13 classes; no dependency
  is added, removed or bumped.

## [1.0.1] - 2026-10-14
### Changed
- Metadata-only release (INV-T-4310, coordinator ruling INV-Q-4552): no behaviour or API change.
- The POMs of both publications (api and impl) declare the company licence, `Dynokore Proprietary Licence`
  (SPDX ref `LicenseRef-Dynokore-Proprietary`, no URL yet), stamped by platform-build 0.11.0 (INV-T-4310; owner
  rulings INV-Q-4286 (C) and INV-Q-4284 (A), STK-66, INV-REQ-1383).
- Source formatted to ktlint_official (platform-build 0.11.0, INV-Q-4426, INV-T-4556); formatting only.

## [1.0.0] - 2026-10-13
### Added
- The framework tenant predicate on jOOQ OSS 3.21 (INV-T-4133; INV-ADR-3968 item 4a, INV-REQ-854 b2 and b6; owner
  ruling INV-Q-4113): `TenancyJooq.configure` installs a VisitListener that injects the bound tenant's predicate into
  every SELECT, UPDATE, DELETE, INSERT and MERGE, an ExecuteListener that checks it independently before the
  statement runs, and a ConnectionProvider over tenancy-kernel's current transaction connection.
- `TableClass`, `TableClasses` and `TableClasses.verify(schema)` (start-up check of the declared table classes),
  `TenancyTables.declared` and `TenancyJooq.dslContext` for platform-libs modules, `TenantPredicateViolation`.
- Test fixture `TenancyProbe` with its Spring test auto-configuration (coordinator ruling INV-Q-4140; owner ruling
  INV-Q-4196).
