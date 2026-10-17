# Changelog

All notable changes to `integration-canon` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.0] - 2026-10-18
### Added
- `SpecVersion`, `ReleaseOrder`, `AcceptanceEntry` and `AcceptanceCalendar`: the accepted versions on a date, oldest
  first by the host's release order (INV-REQ-589).
- `selectVersion`: the oldest version accepted on the submission date that is not older than the required one, or
  `NoAcceptedVersion`; never a fallback (INV-REQ-590).
- `selectRepresentable` and `Representability`: the selected version only is checked; `Blocked` otherwise, nothing
  re-coded (INV-REQ-592).
- `ProjectionRegistry`: one projection per version, several at once (INV-REQ-488).
