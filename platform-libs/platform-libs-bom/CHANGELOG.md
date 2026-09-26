# Changelog

All notable changes to `platform-libs-bom` are recorded here (FR-L.6). Format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.3.0] - 2026-09-27
### Added
- `rulepack-kernel-api`/`rulepack-kernel` constraint pair, pinned to
  `rulepack-kernel`'s own `1.0.0` (INV-T-2866 / T22: the module's first real
  interface).
- `evidence-store-api`/`evidence-store` constraint pair, pinned to
  `evidence-store`'s own `1.0.0` (INV-T-2863 / T19: the module's first real
  interface).

## [0.2.0] - 2026-09-26
### Added
- `tenancy-kernel-api`/`tenancy-kernel` constraint pair, pinned to
  `tenancy-kernel`'s own `1.0.0` (INV-T-2861 / T17: the module's first real
  interface). The first non-empty entry in this BOM.
- `adapter-runtime-api` / `adapter-runtime` 1.0.0 constraint pair (INV-T-2862 / T18),
  the first real entries beyond the empty scaffold.

## [0.1.0] - 2026-09-26
### Added
- BOM scaffold: `java-platform`, Maven publish, no constraints yet (INV-T-2860).
  "An empty module set releases end to end" — proven by this train's pipeline,
  not by a fixture, since it is this repository's genuine current state: every
  Wave 1 module is still a design-only scaffold (repository README.md). A
  module gains its `api`/impl` constraint pair here in the same merge request
  that gives it a real interface.
