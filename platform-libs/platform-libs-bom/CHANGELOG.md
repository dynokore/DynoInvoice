# Changelog

All notable changes to `platform-libs-bom` are recorded here (FR-L.6). Format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.1.0] - 2026-09-26
### Added
- BOM scaffold: `java-platform`, Maven publish, no constraints yet (INV-T-2860).
  "An empty module set releases end to end" — proven by this train's pipeline,
  not by a fixture, since it is this repository's genuine current state: every
  Wave 1 module is still a design-only scaffold (repository README.md). A
  module gains its `api`/impl` constraint pair here in the same merge request
  that gives it a real interface.
