# Changelog

All notable changes to `config-resolver` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.3.0] - 2026-10-11
### Added
- `TypedValueSpec.default` (gst-catalogue 1.6.0 `TypedValue.default`) and `TypedValueSpec.absentAs`: an option
  without a value counts by its catalogue default, else its fixed number (INV-T-3381).

### Changed
- VP-08's DEFAULT_PAIR_4H window is no longer treated as fixed at 4 h (INV-Q-3204, superseded by owner ruling
  INV-Q-3320 A′): it is checked against the catalogue's typed bounds (1 to 8 hours, default 4, DOWN_STRICTER),
  where nothing is set at a scope the catalogue default resolves with its default window (`{"window":4}`) and
  counts as 4 h in the cross-scope check and the cap; a write without a value or without the window is still
  refused (INV-Q-3167, INV-Q-3548); tighten-only across scopes is unchanged (INV-REQ-1269, INV-REQ-896 b2,
  INV-Q-3206).
- A typed catalogue-default option resolves with the value built from its typed defaults (a number with a
  numeric `default`, or a record whose every field has one); otherwise without a value, as before.

## [1.2.0] - 2026-10-11
### Added
- Cross-scope tighten-only (VP-08): a narrower value's directed fields may not be looser than the broader
  scope's value, compared by field name; undirected fields are never compared (INV-REQ-780 b1, INV-Q-2936,
  INV-Q-3204). Refusal `LOOSER_THAN_BROADER`.
- Installation bound (VP-57, VP-67): `Bound.row`, `VariationPointDefinition.installationParameter`; the
  bounding INSTALLATION row is resolved in the same service (INV-REQ-1248, INV-Q-3202). Refusal
  `BEYOND_INSTALLATION_BOUND`; an unevaluable bound refuses `BOUND_UNAVAILABLE` (fail closed).
- Resolution caps a stored value's limited fields at the broader value or bound in force on the date, the
  stored value unchanged: `ResolvedSetting.caps`, `PolicyCap`, `PolicyReader.capToBounds` for per-object
  values, `BoundUnavailable` (INV-REQ-1242 b5, INV-Q-3206).
- `ConfigSchema.upgradeTo120`: `config_resolved_value.capped_by`; `RecordedResolution.caps`.
- `TypedValueSpec.boundingRows`, `TypedValueSpec.fixed`.

### Changed
- The 1.1.0 `NOT_SUPPORTED` refusal now applies only to what cannot be evaluated: an installation bound with no
  installation parameter row, or a cross-scope row whose options do not all carry the same directed fields
  (INV-Q-3173).

## [1.1.0] - 2026-10-11
### Added
- `PolicyWriter`: `setPolicy` at every allowed scope including installation, with role, reason, bounds,
  effective-date and back-dating checks, an audit entry holding the prior value and `PolicyChanged` through
  the outbox where present; `acknowledgeControlRelaxation`; `validate` (INV-T-2870, INV-AC-2227,
  INV-AC-2224, INV-AC-2229, INV-AC-205, INV-AC-2230, INV-AC-2234).
- `PolicyHistory`: versions, as-of view and compare (INV-AC-1146).
- Ports `DelegationProbe`, `BackdatingFloor`, `ValueConstraints`; `VariationPointCatalogue.keys()`;
  `OptionSpec.typedValue` and `OptionSpec.acknowledgers` (INV-Q-3134..3142, INV-Q-3165..3173).
- `ConfigSchema.upgradeTo110`: `config_relaxation_acknowledgement` (RLS forced, insert-only for the app role)
  and `config_setting.relaxation_ref`; `ConfigPurge.purgeTenant` also removes acknowledgements.

## [1.0.0] - 2026-10-11
### Added
- `PolicyReader` over seven scope levels, then the operating profile at its version, then the catalogue
  default (by form where the row says so), at the transaction date (INV-REQ-1068, INV-REQ-1242,
  INV-REQ-1351, INV-REQ-1251; INV-T-2869).
- `VariationPointCatalogue` port shaped on gst-catalogue 1.2.0: scope levels, option keys, per-option
  strictness, multi-select, by-form default (INV-Q-2934, INV-Q-2936).
- `ResolutionRecorder` (insert-only, first write wins) and `ConfigPurge` (`purgeSubject`, `purgeTenant`)
  (INV-Q-2938).
- `BusinessDate.today(clock)`: the Asia/Singapore business date for as-of views (INV-Q-2940).
- `ConfigSchema`: `config_setting` (source `OVERRIDE`/`PROFILE`, profile id and version, INV-Q-2979),
  `config_installation_setting`, `config_resolved_value`; RLS enabled and forced on the tenant tables.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
