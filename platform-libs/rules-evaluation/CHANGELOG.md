# Changelog

All notable changes to `rules-evaluation` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.2.0] - 2026-11-10
### Added
- Slice 3 of 3 (INV-T-19456, INV-Q-8719, INV-AC-19453): the document classification moved from svc-rules
  `rules-domain` (package `classification`: `DocumentClassifier`, `ClassificationTable`, `Classification`), with
  `ScopePackContents.decodeClassificationTable`; and the composed `evaluate(request, packs, mapping, profile)` returning
  `EvaluationResult` (categoryCode, scopeVerdict, documentTypeCode). A refusal is a thrown `EvaluationRefusal`
  (`PackAbsent`, `UnmappedCode`, `NoClassificationRow`, `LineRefused`, `ClassificationHeld`); no field is defaulted. `EvaluationProfile` (obligationStartDate,
  registrationEndDate, voluntaryOverSubmission, partiallyExempt, election) is the explicit input of the scope facts.
  `NoClassificationRow.code` is `ErrorCode.RUL_012`; the `gstCatalogue` pin rises from 2.11.0 to 2.57.0.

## [1.1.0] - 2026-11-10
### Added
- Slice 2 of 3 (INV-T-19455, INV-Q-8719, INV-AC-19452): the scope verdict rules moved from svc-rules `rules-domain`:
  `ScopeRules`, `PartialExemption`, `ScopeVerdict` and `ScopeVerdictPort` (package `scope`), and
  `ScopePackContents.decodeScopeData`. `ScopeEvaluation` fills `EvaluationResult.scopeVerdict` with the verdict name
  exactly as the port gives it; no port raises `ScopeRulesMissing` and a port exception propagates (no default verdict).

## [1.0.0] - 2026-11-10
### Added
- The L3 module, slice 1 of 3 (INV-T-19454, INV-Q-8719): `LineCoder`, `CodeCatalogue`, `ScenarioData`,
  `SchemeTagAuthoring` and `MappingCodeChecks` moved from svc-rules `rules-domain`, with `SourceClass`,
  `TransactionDate`, `PackEntry` and `MappingSignOff`; the decoders `CodeCataloguePackContents`,
  `MappingPackContents` and `ScenarioPackContents` with `PackContentsFailure`; `TenantMapping` (no default entry) and
  `EvaluationResult` (the scope verdict and the document type code stay null until slices 2 and 3).
