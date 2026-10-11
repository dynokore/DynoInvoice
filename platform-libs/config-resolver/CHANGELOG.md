# Changelog

All notable changes to `config-resolver` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.0] - 2026-10-11
### Added
- `PolicyReader` over seven scope levels, then the operating profile at its version, then the catalogue
  default (by form where the row says so), at the transaction date (INV-REQ-1068, INV-REQ-1242,
  INV-REQ-1351, INV-REQ-1251; INV-T-2869).
- `VariationPointCatalogue` port shaped on gst-catalogue 1.2.0: scope levels, option keys, per-option
  strictness, multi-select, by-form default (INV-Q-2934, INV-Q-2936).
- `ResolutionRecorder` (insert-only, first write wins) and `ConfigPurge` (`purgeSubject`, `purgeTenant`)
  (INV-Q-2938).
- `ConfigSchema`: `config_setting` (source `OVERRIDE`/`PROFILE`, profile id and version, INV-Q-2979),
  `config_installation_setting`, `config_resolved_value`; RLS enabled and forced on the tenant tables.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
