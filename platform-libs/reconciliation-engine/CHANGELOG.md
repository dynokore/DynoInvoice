# Changelog

All notable changes to `reconciliation-engine` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.0] - 2026-10-17
### Added
- `Comparisons.compare`: two-sided comparison by opaque key, exact `BigDecimal` amounts, variance `return - submitted`,
  the missing side named (INV-REQ-631).
- `Category`, `ClassificationRule`, `RuleSet`: the difference taxonomy as host data, key equality only (INV-REQ-632).
- `Classifier`: classified variances and the unexplained residual per line, never clamped, no tolerance
  (INV-REQ-633, INV-AC-1807).
- `WorkPaper` with header, comparison, classified variances, unexplained residual, attestation and `reconciledTo`,
  and the plain-value export `toRows()` (INV-REQ-947); a header key `reconciledTo` is refused with
  `ReservedHeaderKeyException` (INV-Q-6066).
- `ReconciliationEngine` facade in the impl half.
