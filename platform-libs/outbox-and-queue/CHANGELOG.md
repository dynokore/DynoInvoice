# Changelog

All notable changes to `outbox-and-queue` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.0] - 2026-10-10
### Added
- Per-service outbox written in the command transaction; envelope (UUIDv7 id, per-key
  `docsequence`) stamped on write; emission guard against the event catalogue
  (INV-REQ-815, -1162, -1211; INV-AC-1869, -2184).
- Relay to the `EventBinding`, with the PostgreSQL event-log binding and reader; producers
  keep committing through an event-log outage and the relay catches up in per-key order
  (INV-REQ-1205, -1212; INV-AC-2185).
- Inbox consumer: inbox row, `docsequence` stale rule, offset and handler in one
  transaction; exactly-once after a kill before commit; dead letter for malformed records
  (INV-REQ-1164, -1221, -1222; INV-AC-2149, -2204, -2205).
- Test fixtures: PostgreSQL 17/18 Testcontainers fixture, flaky event-log DataSource,
  killable transaction boundary.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
