# Changelog

All notable changes to `evidence-store` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

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
