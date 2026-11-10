# Changelog

All notable changes to `rules-evaluation` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.0] - 2026-11-10
### Added
- The L3 module, slice 1 of 3 (INV-T-19454, INV-Q-8719): `LineCoder`, `CodeCatalogue`, `ScenarioData`,
  `SchemeTagAuthoring` and `MappingCodeChecks` moved from svc-rules `rules-domain`, with `SourceClass`,
  `TransactionDate`, `PackEntry` and `MappingSignOff`; the decoders `CodeCataloguePackContents`,
  `MappingPackContents` and `ScenarioPackContents` with `PackContentsFailure`; `TenantMapping` (no default entry) and
  `EvaluationResult` (the scope verdict and the document type code stay null until slices 2 and 3).
