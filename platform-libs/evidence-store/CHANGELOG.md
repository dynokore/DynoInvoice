# Changelog

All notable changes to `evidence-store` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [2.0.4] - 2026-10-18
### Changed
- Signed binding, slice 8 of 9 of INV-T-4156 (INV-T-5365, INV-Q-5352, INV-Q-5354 (A)): `EvidenceStorePostgresFixture` hands out a `keys` source from tenancy-kernel's `SignedBinding` and the Postgres integration test builds its runners with it; test code only.

## [2.0.3] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5359, G-DVS): depends on `tenancy-kernel` 3.3.0, whose published module metadata changed; no code change.

## [2.0.2] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5358, G-DVS): depends on `tenancy-kernel` 3.2.0, whose published module metadata gained a jOOQ dependency; no code change.

## [2.0.1] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5820, ruling INV-Q-5896 A): depends on `tenancy-jooq` 1.2.0 (the guard refuses a `TenancyTables` not built by `TableClasses.verify`, INV-Q-4821 B); no code change.

## [2.0.0] - 2026-10-17
### Changed
- BREAKING (INV-T-4516, slice 3 of 3; owner rulings INV-Q-4196 (A), INV-Q-4508 (A)): `JdbcEvidenceLedger` and `JdbcLegalHoldRegistry` run every statement through jOOQ on a `DSLContext` from tenancy-jooq's factory (`TenancyJooq.dslContext`), with the module's table named `DSL.name(schema, table)` and declared `TENANT`; identifiers are `DSL.name(...)` of `SqlIdentifier`-validated names and every value is a bind value; the chain's advisory lock is a jOOQ function call (`pg_advisory_xact_lock(hashtext(?), hashtext(?))`, both keys bind values). Their first constructor parameter is that `DSLContext`; the `CurrentConnection` parameter is gone, so they run on the `TransactionRunner` whose data source holds the lane gate (lane behaviour unchanged, INV-Q-5243). Behaviour is unchanged. `evidence-store` depends on tenancy-jooq.
- The module's tests reach the database only through `TestPoolFixture`, a `TransactionRunner` and a tenancy-jooq `DSLContext`; `EvidenceStorePostgresFixture` creates its roles and tables in the container's init script and no longer hands out data sources (`ownerDataSource`, `appDataSource`, `createTables` removed).

## [1.0.7] - 2026-10-16
### Changed
- Metadata only: depends on `tenancy-kernel` 3.1.0 (adds `TransactionRunner.outsideOpenTransaction`, INV-T-5675); version step per ruling INV-Q-5678 (A). No code change.

## [1.0.6] - 2026-10-16
### Changed
- Metadata-only release (INV-T-4515, version-file scope of the downstream closure): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs and module metadata of the publications name the sibling modules at their current versions (tenancy-kernel 3.0.0, outbox-and-queue 3.1.0 and, where depended on, tenancy-jooq 1.1.0 and service-runtime 8.0.0).

## [1.0.5] - 2026-10-16
### Changed
- Metadata-only release (INV-T-5339, coordinator ruling INV-Q-5337, delegated, owner 2026-10-14): no API or behaviour
  change; `src/main` and `src/impl` are unchanged since 1.0.4.
- The POMs and module metadata of both publications (api and impl) now name the sibling modules at their current
  versions: tenancy-kernel 2.0.0 (was 1.4.1), crypto 1.1.0 (was 1.0.2).

## [1.0.4] - 2026-10-14
### Changed
- Metadata-only release (INV-T-4310, coordinator ruling INV-Q-4552): no behaviour or API change.
- The POMs of both publications (api and impl) declare the company licence, `Dynokore Proprietary Licence`
  (SPDX ref `LicenseRef-Dynokore-Proprietary`, no URL yet), stamped by platform-build 0.11.0 (INV-T-4310; owner
  rulings INV-Q-4286 (C) and INV-Q-4284 (A), STK-66, INV-REQ-1383).
- Source formatted to ktlint_official (platform-build 0.11.0, INV-Q-4426, INV-T-4556); formatting only.

## [1.0.3] - 2026-10-13
### Fixed
- The published impl POM (`evidence-store`) declared no dependency (INV-T-4356; defect in INV-T-2860's and
  INV-T-3937's per-module publishing, STK-60, STK-01 rule (a), TPL-01): the `impl` publication was built with
  `artifact(implJar)` and no software component, so a consumer declaring platform-libs-bom and this impl artefact had
  to list `evidence-store-api` and the other modules itself. The impl POM now declares `evidence-store-api` at the
  same version and the first-level dependencies of the impl runtime classpath, generated and checked by the root
  build.gradle.kts (`verifyImplPoms`): crypto-api:1.0.1 (runtime), evidence-store-api:1.0.3 (compile),
  tenancy-kernel:1.3.1 (compile), tenancy-kernel-api:1.3.1 (runtime), org.jetbrains.kotlin:kotlin-stdlib:2.4.20
  (compile), software.amazon.awssdk:s3:2.55.6 (compile). The classes are unchanged.

## [1.0.2] - 2026-10-12
### Changed
- `JdbcEvidenceLedger`, `JdbcLegalHoldRegistry` and `EvidenceLedgerSchema` interpolate identifiers only through
  tenancy-kernel's `SqlIdentifier.require` (tenancy-kernel 1.3.0); their own regex validators are removed. A
  name with a leading underscore, longer than 63 characters, or whose derived trigger names
  (`<table>_immutable`, `<table>_no_mutation`) exceed 63 characters is now refused instead of being silently
  truncated by PostgreSQL. The SQL for a valid name is unchanged; `EvidenceLedgerSchema.trailerDdl` joins the
  RLS trailer to its own statements instead of splicing it into them. The module has zero platform-build 0.9.0
  SQL guard findings (INV-REQ-854 b5, INV-ADR-3968 item 11; coordinator rulings INV-Q-4036, INV-Q-4040;
  INV-T-4043).

## [1.0.1] - 2026-10-11
### Changed
- `ContentHash.of` hashes through the platform `crypto` module (`Hashing`, `HashAlgorithm.SHA_256`;
  CS-53, INV-T-3358 / FU-E2) instead of calling `MessageDigest` itself. Same signature, same
  output; `evidence-store-api` now depends on `crypto-api`.

## [1.0.0] - 2026-09-27
### Added
- `EvidenceLedger` port and `JdbcEvidenceLedger`: an append-only, hash-chained
  evidence ledger scoped by (tenant, entity, period) — SHA-256 per record,
  chained to the previous record (FR-9.15), indexed retrieval and bulk export
  scoped to entity and period (done-when), and `verifyChain` chain
  verification, backed by a pure `HashChain.verify` (STK-38 property-testable
  without a database). Concurrent appenders on the same chain are serialised
  with a transaction-scoped advisory lock (CS-21's own pattern), not a
  row-level `FOR UPDATE` lock — which the application role's revoked UPDATE
  grant (CS-22) would itself refuse.
- `EvidenceLedgerSchema`: the append-only migration trailer (CS-19, CS-22) —
  reuses `TenantRlsMigration`'s RLS DDL directly (tenancy-kernel), plus a
  `REVOKE UPDATE, DELETE, TRUNCATE` and a `BEFORE UPDATE OR DELETE` trigger
  that refuses mutation regardless of role, including the owner (done-when:
  "in-place update refused by DB grants and trigger").
- `LegalHoldRegistry` port and `JdbcLegalHoldRegistry`, `RetentionPeriod`
  (floor of five years, FR-D.3) and `RetentionGate` (FR-D.4: a hold always
  refuses deletion).
- `ObjectStore` port and `S3ObjectStore`: object storage under object lock in
  compliance mode (STK-20, AWS SDK v2) — no `delete`/`update` method exists on
  the port at all.
- Isolation-test fixtures (`java-test-fixtures`): a real PostgreSQL
  Testcontainers instance (`EvidenceStorePostgresFixture`), reusing
  tenancy-kernel's own `TenantAwareDataSource` and `TenantRlsMigration`
  directly rather than reimplementing them.
- Property tests for chain integrity (STK-38, `kotest-property`), Testcontainers
  integration tests against PostgreSQL 17 and 18 proving the append-only
  grants and trigger, tenant isolation and tampering detection, a MinIO
  integration test proving the object-lock "put, retain, refuse delete"
  guarantee (the object-lock simulator image is not available yet, AGT-13),
  and a consumer-driven contract test of the module API.
- `platform-libs-bom` gains this module's `api`/`impl` constraint pair.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
