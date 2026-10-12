# Changelog

All notable changes to `adapter-runtime` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [2.0.1] - 2026-10-12
### Fixed
- The published `adapter-runtime-api` jar 2.0.0 held no api classes (INV-T-3937; coordinator ruling INV-Q-3929,
  defect in INV-T-2860's per-module publishing, STK-60, FR-L.6): `implJar` wrote the same build/libs file as the api
  `jar`, so the `-api` jar is byte-identical to `adapter-runtime` and holds only the impl classes. Fixed on main by
  df85a19 (`implJar` writes to build/libs-impl) and released here. No source or API change.

## [2.0.0] - 2026-09-27
### Changed
- **BREAKING:** `MountedFileSecretStore` implementation removed from adapter-runtime.
  The `SecretStore` port and types (`Secret`, `SecretRef`, `SecretNotFound`) remain in
  `adapter-runtime-api` (STK-60 L1 module policy: each L1 declares its ports, but
  binding implementations live only in `service-runtime`). Services bind
  adapter-runtime's `SecretStore` port by delegating to `service-runtime`'s
  `SecretStore` binding (INV-T-2862 follow-up, no L1→L1 import).

### Removed
- Implementation class `MountedFileSecretStore` and its tests
  (`MountedFileSecretStoreTest`, `MountedFileSecretStoreContractTest`; the port
  contract test `SecretStoreContractTest` and test fixture `FakeSecretStore` remain).

## [1.0.0] - 2026-09-27
### Added
- Adapter lifecycle (`ManagedAdapter`, `AdapterState`) and version cutover routing
  (`AdapterVersion`, `CutoverRule`, `TimeBasedCutoverRule`, `VersionedAdapterRouter`):
  two versions of an adapter run side by side, routed by rule, with in-flight work on
  the retired version finishing before it stops (INV-REQ-1354).
- `ResilientExecutor` and its Resilience4j core implementation
  (`Resilience4jResilientExecutor`, STK-24): timeout, exponential backoff with
  jitter, bounded retry ending in a `DeadLetterSink` entry, circuit breaking and a
  semaphore bulkhead, per edge (INV-REQ-1353).
- `SecretStore` port and its mounted-file implementation (`MountedFileSecretStore`,
  STK-29, CS-24): credentials are read fresh on every call, so rotation needs no
  restart.
- `ConnectionStatusReporter` port and its in-memory implementation
  (`InMemoryConnectionStatusReporter`) for the §35.13 connection contract.
- Unit, property (Kotest, exponential-backoff-with-jitter and value-class
  invariants), port-contract and architecture (Konsist, §18.9 L1 order) tests.
- `platform-libs-bom` entry for `adapter-runtime-api` / `adapter-runtime` 1.0.0.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
