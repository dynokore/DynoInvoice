# Changelog

All notable changes to `tenancy-jooq` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

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
