# Changelog

All notable changes to `document-lifecycle` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.1.1] - 2026-10-19
### Changed
- detekt type-resolution findings fixed (platform-build 0.21.0, INV-Q-4629); no behaviour or API change

## [1.1.0] - 2026-10-17
### Added
- `ChangeTaxonomy`, `Outcome`, `FieldClassifier` and `classify`: a changed payload is classified field by field against
  a host-supplied taxonomy; the most restrictive class decides; the result carries the outcome and a trace
  (INV-REQ-577, INV-REQ-578). Additive; `StateModel` and `VersionedDocument` are unchanged (INV-T-5961).

## [1.0.0] - 2026-10-17
### Added
- `StateModel`: states (opaque codes) with `inFlight` and `countsAsOpen` flags, allowed transitions and the initial
  state, all data; terminal is derived from "no outgoing transition" (INV-REQ-576).
- `VersionedDocument`: immutable, versions 1, 2, 3 without gaps; `addVersion` appends version n+1 and leaves earlier
  versions unchanged; `transition` throws `IllegalTransitionException` for a move the model does not allow.
- `SupersessionRule` / `SupersessionRules`: older versions in a set of states move to another state when a newer
  version reaches a trigger state; every moved pair must be an allowed transition (INV-REQ-573).
- In-flight guard derived from the `inFlight` flags: `InFlightConflictException` (INV-REQ-571).
- `DocumentLifecycle` facade in the impl half.
