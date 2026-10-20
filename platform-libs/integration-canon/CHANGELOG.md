# Changelog

All notable changes to `integration-canon` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.2.0] - 2026-10-20
### Added
- `SchematronRunner`: compiles an already-XSLT Schematron stylesheet once (Saxon-HE) into a shared, thread-safe
  `CompiledSchematron`; `validate` reads the failed asserts from the in-memory SVRL result. It refuses every external
  resource, extension function and result document, and parses input with `HardenedXml` (CS-25, INV-Q-7068,
  INV-REQ-472).

## [1.1.1] - 2026-10-19
### Changed
- The impl half gains Saxon-HE 12.10 (`implImplementation`) for the Schematron runner; dependency only, no code. The api
  half stays free of Saxon (INV-Q-7068, STK-10, ARC-16).

## [1.1.0] - 2026-10-19
### Added
- `HardenedXml`: the one place that builds the DOM, SAX, StAX and Transformer factories, with document type
  declarations and external entities off (CS-25, INV-Q-7068); tests refuse an XXE and a billion-laughs payload, and
  a gate allows factory construction in that file only.

## [1.0.0] - 2026-10-18
### Added
- `SpecVersion`, `ReleaseOrder`, `AcceptanceEntry` and `AcceptanceCalendar`: the accepted versions on a date, oldest
  first by the host's release order (INV-REQ-589).
- `selectVersion`: the oldest version accepted on the submission date that is not older than the required one, or
  `NoAcceptedVersion`; never a fallback (INV-REQ-590).
- `selectRepresentable` and `Representability`: the selected version only is checked; `Blocked` otherwise, nothing
  re-coded (INV-REQ-592).
- `ProjectionRegistry`: one projection per version, several at once (INV-REQ-488).
